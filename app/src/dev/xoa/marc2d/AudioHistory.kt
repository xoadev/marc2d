package dev.xoa.marc2d

/**
 * The last of the audio given to the wake phrase recognizer. Vosk only confirms the phrase after a moment of silence, and
 * whoever is calling may have started the request by then: without this, its first words never reached Home Assistant,
 * and "¿qué hora es?" was transcribed as "¡Ciertas!".
 */
class AudioHistory(
	private val capacity: Int,
) {
	private val ring = ByteArray(capacity)

	/** Every byte given so far: Vosk's word times count from the first one, even after a reset. */
	var total = 0L
		private set

	fun add(
		bytes: ByteArray,
		length: Int,
	) {
		for (i in 0 until length) ring[((total + i) % capacity).toInt()] = bytes[i]
		total += length
	}

	/** What arrived from [position] on, or as much of it as is kept, starting on a whole sample. */
	fun since(position: Long): ByteArray {
		val from = maxOf(position, total - capacity, 0L).let { it + (it and 1L) }
		if (from >= total) return ByteArray(0)
		return ByteArray((total - from).toInt()) { ring[((from + it) % capacity).toInt()] }
	}
}

/**
 * Raises little-endian 16-bit audio by [gain], without wrapping around at the top. From across the room the panel's
 * microphone gave the voice at about -30 dBFS, and Whisper made up words from it.
 */
fun amplify(
	audio: ByteArray,
	gain: Int,
): ByteArray {
	val out = ByteArray(audio.size)
	for (i in 0 until audio.size - 1 step 2) {
		val sample = ((audio[i + 1].toInt() shl 8) or (audio[i].toInt() and 0xFF)) * gain
		val clamped = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
		out[i] = clamped.toByte()
		out[i + 1] = (clamped shr 8).toByte()
	}
	return out
}
