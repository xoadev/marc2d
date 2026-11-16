package dev.xoa.marc2d

import java.nio.ByteBuffer
import kotlin.random.Random

/** The stream's parameters, without start codes, which the SDP needs before the first frame. */
class H264Parameters(
	val sps: ByteArray,
	val pps: ByteArray,
)

/** An access unit in Annex B, as the encoder delivers it. */
class H264Frame(
	val data: ByteArray,
	val timestampUs: Long,
	val keyFrame: Boolean,
)

/** Where each NAL unit of Annex B data starts, right after its start code. */
internal fun nalStarts(annexB: ByteArray): Sequence<Int> =
	sequence {
		var i = 0
		while (i + 2 < annexB.size) {
			if (annexB[i] == ZERO && annexB[i + 1] == ZERO && annexB[i + 2] == ONE) {
				yield(i + 3)
				i += 3
			} else {
				i++
			}
		}
	}

/** Splits Annex B data into NAL units, without their start codes. */
fun nalUnits(annexB: ByteArray): List<ByteArray> {
	val starts = nalStarts(annexB).toList()
	return starts.mapIndexed { index, start ->
		var end = if (index + 1 < starts.size) starts[index + 1] - 3 else annexB.size
		// A four-byte start code leaves its first zero at the end of the previous unit.
		while (end > start && annexB[end - 1] == ZERO) end--
		annexB.copyOfRange(start, end)
	}
}

/** Finds the SPS and the PPS in what the encoder sends as its configuration. */
fun h264Parameters(annexB: ByteArray): H264Parameters? {
	val units = nalUnits(annexB).filter { it.isNotEmpty() }
	val sps = units.firstOrNull { it[0].nalType == NAL_SPS } ?: return null
	val pps = units.firstOrNull { it[0].nalType == NAL_PPS } ?: return null
	return H264Parameters(sps, pps)
}

/** The type of a NAL unit, from its first byte. A FU-A header keeps the type of the unit it carries in the same bits. */
internal val Byte.nalType: Int
	get() = toInt() and 0x1F

/** What both tracks share: the RTP header, and the reports that let clients line up their clocks. */
internal class RtpWriter(
	private val payloadType: Int,
	private val clockRate: Int,
	private val ssrc: Int,
	/** Where the RTP clock starts. RFC 3550 asks for a random one; the tracks the panel serves have always started at zero. */
	private val initialTimestamp: Long = 0,
) {
	private var sequence = Random.nextInt(0x10000)
	private var packets = 0
	private var octets = 0
	private var lastReportUs: Long? = null

	fun packet(
		payload: ByteArray,
		timestampUs: Long,
		marker: Boolean,
	): ByteArray {
		val packet = rtpHeader(payloadType, sequence, rtpTime(timestampUs), ssrc, marker) + payload
		sequence = (sequence + 1) and 0xFFFF
		packets++
		octets += payload.size
		return packet
	}

	/**
	 * An RTCP sender report (RFC 3550), every few seconds. Without it, a client has no way to know how the timestamps of
	 * one track relate to the other, and sound and video can drift apart.
	 */
	fun senderReportIfDue(timestampUs: Long): ByteArray? {
		val last = lastReportUs
		if (last != null && timestampUs - last < REPORT_INTERVAL_US) return null
		lastReportUs = timestampUs
		val seconds = timestampUs / MICROS_PER_SECOND
		val fraction = (timestampUs % MICROS_PER_SECOND) * (1L shl 32) / MICROS_PER_SECOND
		return ByteBuffer
			.allocate(SENDER_REPORT_BYTES)
			.put(RTP_VERSION_2)
			.put(RTCP_SENDER_REPORT)
			.putShort((SENDER_REPORT_BYTES / 4 - 1).toShort())
			.putInt(ssrc)
			.putInt(seconds.toInt())
			.putInt(fraction.toInt())
			.putInt(rtpTime(timestampUs).toInt())
			.putInt(packets)
			.putInt(octets)
			.array()
	}

	private fun rtpTime(timestampUs: Long): Long = initialTimestamp + timestampUs * clockRate / MICROS_PER_SECOND
}

/** RFC 6184, with single NAL units and FU-A: every client of the panel understands both. */
class H264Packetizer(
	private val maxPacket: Int = MAX_RTP_PACKET,
	ssrc: Int = Random.nextInt(),
) {
	private val writer = RtpWriter(RTP_PAYLOAD_TYPE, VIDEO_CLOCK, ssrc)

	fun packets(frame: H264Frame): List<ByteArray> {
		val units = nalUnits(frame.data).filter { it.isNotEmpty() }
		return units.flatMapIndexed { index, unit ->
			val isLastUnit = index == units.lastIndex
			if (unit.size + RTP_HEADER <= maxPacket) {
				listOf(writer.packet(unit, frame.timestampUs, isLastUnit))
			} else {
				fragments(unit, frame.timestampUs, isLastUnit)
			}
		}
	}

	fun senderReportIfDue(timestampUs: Long): ByteArray? = writer.senderReportIfDue(timestampUs)

	private fun fragments(
		unit: ByteArray,
		timestampUs: Long,
		isLastUnit: Boolean,
	): List<ByteArray> {
		val indicator = ((unit[0].toInt() and 0xE0) or FU_A).toByte()
		val type = unit[0].nalType
		val room = maxPacket - RTP_HEADER - 2
		val chunks = (1 until unit.size step room).toList()
		return chunks.mapIndexed { index, start ->
			val end = minOf(start + room, unit.size)
			val isLast = index == chunks.lastIndex
			val header = type or (if (index == 0) FU_START else 0) or (if (isLast) FU_END else 0)
			writer.packet(byteArrayOf(indicator, header.toByte()) + unit.copyOfRange(start, end), timestampUs, isLast && isLastUnit)
		}
	}
}

/** What the SDP needs to describe the sound: `config` is the encoder's AudioSpecificConfig. */
class AacConfig(
	val sampleRate: Int,
	val channels: Int,
	val config: ByteArray,
)

/** A raw AAC frame, without ADTS headers, as the encoder delivers it. */
class AacFrame(
	val data: ByteArray,
	val timestampUs: Long,
)

/** RFC 3640 in AAC-hbr mode: the one that ffmpeg and go2rtc read, with one frame per packet. */
class AacPacketizer(
	sampleRate: Int,
	ssrc: Int = Random.nextInt(),
) {
	private val writer = RtpWriter(RTP_AUDIO_PAYLOAD_TYPE, sampleRate, ssrc)

	fun packet(frame: AacFrame): ByteArray {
		val size = frame.data.size shl AU_INDEX_BITS
		val header = byteArrayOf(0, AU_HEADER_BITS, (size shr 8).toByte(), size.toByte())
		return writer.packet(header + frame.data, frame.timestampUs, true)
	}

	fun senderReportIfDue(timestampUs: Long): ByteArray? = writer.senderReportIfDue(timestampUs)
}

const val RTP_PAYLOAD_TYPE = 96
const val RTP_AUDIO_PAYLOAD_TYPE = 97
private const val MAX_RTP_PACKET = 1400
private const val RTP_HEADER = 12

/** The clock of H.264 over RTP, as RFC 6184 sets it. */
internal const val VIDEO_CLOCK = 90_000
private const val RTP_VERSION_2: Byte = 0x80.toByte()
private const val RTCP_SENDER_REPORT: Byte = 200.toByte()
private const val SENDER_REPORT_BYTES = 28
private const val REPORT_INTERVAL_US = 5_000_000L
private const val AU_HEADER_BITS: Byte = 16
private const val AU_INDEX_BITS = 3
internal const val NAL_IDR = 5
internal const val NAL_SPS = 7
internal const val NAL_PPS = 8
internal const val STAP_A = 24
internal const val FU_A = 28
internal const val FU_START = 0x80
internal const val FU_END = 0x40
private const val ZERO: Byte = 0
private const val ONE: Byte = 1
