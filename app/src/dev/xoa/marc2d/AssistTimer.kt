package dev.xoa.marc2d

import java.util.Locale

/** How long each step of a voice request took, to know where the wait goes. */
class AssistTimer {
	private var startMs: Long? = null
	private var lastMs = 0L

	/** The line to log for this step, or nothing if it says nothing about the time. */
	fun line(
		step: HaProtocol.AssistStep,
		nowMs: Long,
	): String? {
		if (step.type == "run-start") {
			startMs = nowMs
			lastMs = nowMs
			return null
		}
		val start = startMs ?: return null
		val name = NAMES[step.type] ?: return null
		val line = "Voice: $name in ${seconds(nowMs - lastMs)}, ${seconds(nowMs - start)} so far" + step.code?.let { " ($it)" }.orEmpty()
		lastMs = nowMs
		if (step.type == "run-end") startMs = null
		return line
	}

	private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f s", ms / MS_PER_SECOND)

	private companion object {
		const val MS_PER_SECOND = 1000.0
		val NAMES =
			mapOf(
				"stt-vad-start" to "voice started",
				"stt-vad-end" to "voice ended",
				"stt-end" to "transcribed",
				"intent-end" to "understood",
				"tts-end" to "reply ready",
				"error" to "failed",
				"run-end" to "finished",
			)
	}
}
