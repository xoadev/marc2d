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
	/** Old data is hidden: better to show nothing than weather that is no longer current. */
	fun isFresh(now: Instant): Boolean = Duration.between(fetchedAt, now) <= MAX_AGE

	fun upcoming(now: Instant): List<HourForecast> = forecast.filter { it.time > now }.take(FORECAST_HOURS)

	private companion object {
		val MAX_AGE: Duration = Duration.ofHours(1)
		const val FORECAST_HOURS = 8
	}
}

/** How the weather is presented on the main screen. */
object WeatherFormat {
	private val hour = DateTimeFormatter.ofPattern("HH")

	fun temperature(value: Double): String = "${value.roundToInt()}°"

	fun hour(
		time: Instant,
		zone: ZoneId,
	): String = time.atZone(zone).format(hour)

	/** The condition as text. Unknown ones are shown just as they arrive from Home Assistant. */
	fun label(condition: String): String =
		when (condition) {
			"clear-night" -> "Clear"
			"cloudy" -> "Cloudy"
			"exceptional" -> "Exceptional"
			"fog" -> "Fog"
			"hail" -> "Hail"
			"lightning" -> "Thunderstorm"
			"lightning-rainy" -> "Thunderstorm with rain"
			"partlycloudy" -> "Partly cloudy"
			"pouring" -> "Heavy rain"
			"rainy" -> "Rain"
			"snowy" -> "Snow"
			"snowy-rainy" -> "Sleet"
			"sunny" -> "Sunny"
			"windy", "windy-variant" -> "Windy"
			else -> condition
		}

	/** Monochrome symbol: the U+FE0E selector stops Android from drawing it as a color emoji. */
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
