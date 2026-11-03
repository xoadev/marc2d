package dev.xoa.marc2d

import java.io.ByteArrayOutputStream

/** Extracts the JPEGs from an MJPEG stream without reading its headers: the start and end markers are enough. */
class MjpegParser {
	private val frame = ByteArrayOutputStream()
	private var inFrame = false
	private var previous = 0

	fun feed(
		bytes: ByteArray,
		length: Int,
		onFrame: (ByteArray) -> Unit,
	) {
		for (index in 0 until length) {
			val byte = bytes[index].toInt() and BYTE_MASK
			if (inFrame) {
				frame.write(byte)
				if (previous == MARKER && byte == END) {
					onFrame(frame.toByteArray())
					frame.reset()
					inFrame = false
				} else if (frame.size() > MAX_FRAME_BYTES) {
					frame.reset()
					inFrame = false
				}
			} else if (previous == MARKER && byte == START) {
				frame.write(MARKER)
				frame.write(byte)
				inFrame = true
			}
			previous = byte
		}
	}

	private companion object {
		const val BYTE_MASK = 0xFF
		const val MARKER = 0xFF
		const val START = 0xD8
		const val END = 0xD9

		// A corrupt stream must not exhaust memory.
		const val MAX_FRAME_BYTES = 8 * 1024 * 1024
	}
}
