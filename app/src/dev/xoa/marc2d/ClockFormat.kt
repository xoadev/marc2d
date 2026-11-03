package dev.xoa.marc2d

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Cómo se presenta la hora en la pantalla principal. */
object ClockFormat {
	private val time = DateTimeFormatter.ofPattern("HH:mm")
	private val date = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM")

	/** Recorrido del desplazamiento antiquemado, en dp. Se mueve un paso cada minuto. */
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

	/** Milisegundos que faltan para el siguiente minuto. */
	fun millisToNextMinute(now: LocalDateTime): Long = 60_000L - now.second * 1_000L - now.nano / 1_000_000L
}
