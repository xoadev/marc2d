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
