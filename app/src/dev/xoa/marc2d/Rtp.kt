package dev.xoa.marc2d

import java.nio.ByteBuffer

/** 16-bit PCM samples, in either byte order: L16 is big-endian, as RFC 3551 says. */
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
	/** The last packet of a video frame. */
	val marker: Boolean = false,
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
			return RtpPacket(bytes[1].toInt() and 0x7F, sequence, timestamp, offset, end - offset, bytes[1].toInt() and 0x80 != 0)
		}
	}
}

/** The 12 bytes of an RTP header, without CSRCs or extensions. */
internal fun rtpHeader(
	payloadType: Int,
	sequence: Int,
	timestamp: Long,
	ssrc: Int,
): ByteArray =
	ByteBuffer
		.allocate(12)
		.put(0x80.toByte())
		.put(payloadType.toByte())
		.putShort(sequence.toShort())
		.putInt(timestamp.toInt())
		.putInt(ssrc)
		.array()
