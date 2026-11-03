package dev.xoa.marc2d

import java.io.ByteArrayOutputStream

/** Saca los JPEG de un flujo MJPEG sin leer sus cabeceras: basta con las marcas de inicio y fin. */
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

		// Un flujo corrupto no debe agotar la memoria.
		const val MAX_FRAME_BYTES = 8 * 1024 * 1024
	}
}
