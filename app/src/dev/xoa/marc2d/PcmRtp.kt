package dev.xoa.marc2d

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketAddress
import java.net.SocketException
import kotlin.random.Random

/**
 * Uncompressed audio that an intercom sends however the call was negotiated, and that Linphone cannot play: some send it
 * with their own name for it. Dahua, for one, offers `PCM/16000` in little-endian, while the standard L16 is big-endian.
 */
data class PcmFormat(
	val sampleRate: Int,
	val littleEndian: Boolean,
) {
	/** One packet: 20 ms, as intercoms use. */
	val frameSamples: Int
		get() = sampleRate / FRAMES_PER_SECOND

	companion object {
		const val STANDARD = "standard"
		private const val FRAMES_PER_SECOND = 50
		private val FORMATS =
			mapOf(
				"pcm_le_16000" to PcmFormat(16_000, true),
				"pcm_le_8000" to PcmFormat(8_000, true),
				"l16_16000" to PcmFormat(16_000, false),
				"l16_8000" to PcmFormat(8_000, false),
			)

		/** Nothing for the standard audio, which Linphone negotiates and plays: also for a value this version does not know. */
		fun parse(value: String): PcmFormat? = FORMATS[value]
	}
}

fun decodePcm(
	bytes: ByteArray,
	offset: Int,
	length: Int,
	littleEndian: Boolean,
): ShortArray =
	ShortArray(length / 2) { i ->
		val first = bytes[offset + 2 * i].toInt() and 0xFF
		val second = bytes[offset + 2 * i + 1].toInt() and 0xFF
		(if (littleEndian) (second shl 8) or first else (first shl 8) or second).toShort()
	}

fun encodePcm(
	samples: ShortArray,
	littleEndian: Boolean,
): ByteArray {
	val bytes = ByteArray(samples.size * 2)
	samples.forEachIndexed { i, sample ->
		val high = (sample.toInt() shr 8).toByte()
		val low = sample.toByte()
		bytes[2 * i] = if (littleEndian) low else high
		bytes[2 * i + 1] = if (littleEndian) high else low
	}
	return bytes
}

class RtpPacket(
	val payloadType: Int,
	val sequence: Int,
	val timestamp: Long,
	val payloadOffset: Int,
	val payloadLength: Int,
) {
	companion object {
		private const val HEADER = 12

		/** Nothing if it is not RTP version 2, or its lengths do not add up. */
		fun parse(
			bytes: ByteArray,
			length: Int,
		): RtpPacket? {
			if (length < HEADER || (bytes[0].toInt() and 0xC0) != 0x80) return null
			val csrcs = bytes[0].toInt() and 0x0F
			var offset = HEADER + 4 * csrcs
			if (bytes[0].toInt() and 0x10 != 0) {
				if (length < offset + 4) return null
				val words = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
				offset += 4 + 4 * words
			}
			var end = length
			if (bytes[0].toInt() and 0x20 != 0) end -= bytes[length - 1].toInt() and 0xFF
			if (end < offset) return null
			val sequence = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
			val timestamp =
				(0 until 4).fold(0L) { all, i -> (all shl 8) or (bytes[4 + i].toLong() and 0xFF) }
			return RtpPacket(bytes[1].toInt() and 0x7F, sequence, timestamp, offset, end - offset)
		}
	}
}

/** Whether [sequence] comes after [last], with the 16-bit counter going round. */
fun isNewer(
	sequence: Int,
	last: Int,
): Boolean {
	val distance = (sequence - last) and 0xFFFF
	return distance in 1 until 0x8000
}

/**
 * The intercom's uncompressed audio over RTP, beside Linphone, which keeps the call and the video. It shares Linphone's audio
 * port: the last socket bound there with address reuse is the one that gets what arrives.
 *
 * What to send, and where, is learnt from what arrives: the intercom's packets come from where it expects ours, with the
 * payload type it uses. Packets of the codec Linphone negotiated, or too short to be audio, are not it.
 */
class PcmRtpLink(
	private val socket: DatagramSocket,
	private val format: PcmFormat,
	private val negotiatedPayloadType: Int,
	private val play: (ShortArray) -> Unit,
) {
	@Volatile private var remote: SocketAddress? = null

	@Volatile private var payloadType: Int? = null

	private var last: Int? = null
	private val writer by lazy { RtpWriter(payloadType ?: 0, format.sampleRate, Random.nextInt()) }
	private var sent = 0L

	val isHeard: Boolean
		get() = payloadType != null

	/** Until the socket closes. */
	fun receive() {
		val buffer = ByteArray(MAX_PACKET)
		while (true) {
			val packet = DatagramPacket(buffer, buffer.size)
			try {
				socket.receive(packet)
			} catch (_: SocketException) {
				return
			}
			val rtp = RtpPacket.parse(buffer, packet.length) ?: continue
			if (!isTheirAudio(rtp)) continue
			if (payloadType == null) {
				remote = packet.socketAddress
				payloadType = rtp.payloadType
			}
			if (rtp.payloadType != payloadType) continue
			val previous = last
			// A packet that arrives late would only play out of place.
			if (previous != null && !isNewer(rtp.sequence, previous)) continue
			last = rtp.sequence
			play(decodePcm(buffer, rtp.payloadOffset, rtp.payloadLength, format.littleEndian))
		}
	}

	private fun isTheirAudio(rtp: RtpPacket): Boolean =
		rtp.payloadType != negotiatedPayloadType &&
			rtp.payloadType in DYNAMIC_TYPES &&
			rtp.payloadLength >= MIN_AUDIO_BYTES &&
			rtp.payloadLength % 2 == 0

	/** Sends a frame of the microphone, once there is somewhere to send it. */
	fun send(samples: ShortArray): Boolean {
		val to = remote ?: return false
		val timestampUs = sent * MICROS / format.sampleRate
		val packet = writer.packet(encodePcm(samples, format.littleEndian), timestampUs, false)
		sent += samples.size
		socket.send(DatagramPacket(packet, packet.size, to))
		return true
	}

	private companion object {
		const val MAX_PACKET = 2048
		const val MIN_AUDIO_BYTES = 40
		const val MICROS = 1_000_000L
		val DYNAMIC_TYPES = 96..127
	}
}
