package dev.xoa.marc2d

import java.nio.ByteBuffer

/** 16-bit PCM samples in big-endian, as RFC 3551 says L16 goes. */
fun decodePcm(
	bytes: ByteArray,
	offset: Int,
	length: Int,
): ShortArray = ShortArray(length / 2).also { ByteBuffer.wrap(bytes, offset, length).asShortBuffer().get(it) }

fun encodePcm(samples: ShortArray): ByteArray = ByteBuffer.allocate(samples.size * 2).apply { asShortBuffer().put(samples) }.array()

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
	marker: Boolean = false,
): ByteArray =
	ByteBuffer
		.allocate(12)
		.put(0x80.toByte())
		.put((payloadType or (if (marker) 0x80 else 0)).toByte())
		.putShort(sequence.toShort())
		.putInt(timestamp.toInt())
		.putInt(ssrc)
		.array()

internal const val MICROS_PER_SECOND = 1_000_000L
