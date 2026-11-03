package dev.xoa.marc2d

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

data class HourForecast(
	val time: Instant,
	val condition: String,
	val temperature: Double,
)

data class WeatherReport(
	val condition: String,
	val temperature: Double?,
	val forecast: List<HourForecast>,
	val fetchedAt: Instant,
) {
	/** Un dato viejo se oculta: mejor no mostrar nada que un tiempo que ya no es el actual. */
	fun isFresh(now: Instant): Boolean = Duration.between(fetchedAt, now) <= MAX_AGE

	fun upcoming(now: Instant): List<HourForecast> = forecast.filter { it.time > now }.take(FORECAST_HOURS)

	private companion object {
		val MAX_AGE: Duration = Duration.ofHours(1)
		const val FORECAST_HOURS = 8
	}
}

/** Cómo se presenta el tiempo en la pantalla principal. */
object WeatherFormat {
	private val hour = DateTimeFormatter.ofPattern("HH")

	fun temperature(value: Double): String = "${value.roundToInt()}°"

	fun hour(
		time: Instant,
		zone: ZoneId,
	): String = time.atZone(zone).format(hour)

	/** La condición en texto. Las que no conocemos se muestran tal cual llegan de Home Assistant. */
	fun label(condition: String): String =
		when (condition) {
			"clear-night" -> "Despejado"
			"cloudy" -> "Nublado"
			"exceptional" -> "Excepcional"
			"fog" -> "Niebla"
			"hail" -> "Granizo"
			"lightning" -> "Tormenta"
			"lightning-rainy" -> "Tormenta con lluvia"
			"partlycloudy" -> "Parcialmente nublado"
			"pouring" -> "Lluvia intensa"
			"rainy" -> "Lluvia"
			"snowy" -> "Nieve"
			"snowy-rainy" -> "Aguanieve"
			"sunny" -> "Soleado"
			"windy", "windy-variant" -> "Viento"
			else -> condition
		}

	/** Símbolo monocromo: el selector U+FE0E evita que Android lo pinte como un emoji en color. */
	fun symbol(condition: String): String =
		when (condition) {
			"clear-night" -> "☾︎"
			"cloudy", "partlycloudy" -> "☁︎"
			"fog" -> "▒"
			"hail", "snowy", "snowy-rainy" -> "❄︎"
			"lightning", "lightning-rainy" -> "⚡︎"
			"pouring", "rainy" -> "☂︎"
			"sunny" -> "☀︎"
			"windy", "windy-variant" -> "≈"
			else -> "·"
		}
}
