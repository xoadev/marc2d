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

/** Splits Annex B data into NAL units, without their start codes. */
fun nalUnits(annexB: ByteArray): List<ByteArray> {
	val starts = mutableListOf<Int>()
	var i = 0
	while (i + 2 < annexB.size) {
		if (annexB[i] == ZERO && annexB[i + 1] == ZERO && annexB[i + 2] == ONE) {
			starts += i + 3
			i += 3
		} else {
			i++
		}
	}
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
	val sps = units.firstOrNull { it.nalType == NAL_SPS } ?: return null
	val pps = units.firstOrNull { it.nalType == NAL_PPS } ?: return null
	return H264Parameters(sps, pps)
}

private val ByteArray.nalType: Int
	get() = this[0].toInt() and 0x1F

/** RFC 6184, with single NAL units and FU-A: every client of the panel understands both. */
class H264Packetizer(
	private val maxPacket: Int = MAX_RTP_PACKET,
	private val ssrc: Int = Random.nextInt(),
) {
	private var sequence = Random.nextInt(0x10000)

	fun packets(frame: H264Frame): List<ByteArray> {
		val units = nalUnits(frame.data).filter { it.isNotEmpty() }
		val timestamp = (frame.timestampUs * RTP_CLOCK / 1_000_000).toInt()
		return units.flatMapIndexed { index, unit ->
			val isLastUnit = index == units.lastIndex
			if (unit.size + RTP_HEADER <= maxPacket) {
				listOf(rtp(unit, timestamp, isLastUnit))
			} else {
				fragments(unit, timestamp, isLastUnit)
			}
		}
	}

	private fun fragments(
		unit: ByteArray,
		timestamp: Int,
		isLastUnit: Boolean,
	): List<ByteArray> {
		val indicator = ((unit[0].toInt() and 0xE0) or FU_A).toByte()
		val type = unit[0].toInt() and 0x1F
		val room = maxPacket - RTP_HEADER - 2
		val chunks = (1 until unit.size step room).toList()
		return chunks.mapIndexed { index, start ->
			val end = minOf(start + room, unit.size)
			val isLast = index == chunks.lastIndex
			val header = type or (if (index == 0) FU_START else 0) or (if (isLast) FU_END else 0)
			rtp(byteArrayOf(indicator, header.toByte()) + unit.copyOfRange(start, end), timestamp, isLast && isLastUnit)
		}
	}

	private fun rtp(
		payload: ByteArray,
		timestamp: Int,
		marker: Boolean,
	): ByteArray {
		val packet = ByteBuffer.allocate(RTP_HEADER + payload.size)
		packet.put(RTP_VERSION_2)
		packet.put((RTP_PAYLOAD_TYPE or (if (marker) RTP_MARKER else 0)).toByte())
		packet.putShort(sequence.toShort())
		packet.putInt(timestamp)
		packet.putInt(ssrc)
		packet.put(payload)
		sequence = (sequence + 1) and 0xFFFF
		return packet.array()
	}
}

const val RTP_PAYLOAD_TYPE = 96
private const val MAX_RTP_PACKET = 1400
private const val RTP_HEADER = 12
private const val RTP_CLOCK = 90_000
private const val RTP_VERSION_2: Byte = 0x80.toByte()
private const val RTP_MARKER = 0x80
private const val FU_A = 28
private const val FU_START = 0x80
private const val FU_END = 0x40
private const val NAL_SPS = 7
private const val NAL_PPS = 8
private const val ZERO: Byte = 0
private const val ONE: Byte = 1
