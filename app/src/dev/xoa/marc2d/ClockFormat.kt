package dev.xoa.marc2d

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How the time is presented on the main screen. */
object ClockFormat {
	private val time = DateTimeFormatter.ofPattern("HH:mm")
	private val date = DateTimeFormatter.ofPattern("EEEE, MMMM d")

	/** Path of the anti-burn-in shift, in dp. It moves one step every minute. */
	private val burnInPath =
		listOf(
			0 to 0,
			4 to 2,
			8 to 0,
			4 to -2,
			0 to -4,
			-4 to -2,
			-8 to 0,
			-4 to 2,
		)

	fun time(now: LocalDateTime): String = now.format(time)

	fun date(
		now: LocalDateTime,
		locale: Locale,
	): String =
		now
			.format(date.withLocale(locale))
			.replaceFirstChar { it.titlecase(locale) }

	fun burnInOffset(now: LocalDateTime): Pair<Int, Int> = burnInPath[now.minute % burnInPath.size]

	/** Milliseconds left until the next minute. */
	fun millisToNextMinute(now: LocalDateTime): Long = 60_000L - now.second * 1_000L - now.nano / 1_000_000L
}
