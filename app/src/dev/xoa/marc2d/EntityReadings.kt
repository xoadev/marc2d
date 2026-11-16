package dev.xoa.marc2d

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A value under the time: any entity, which turns to a warning when it crosses its limits. */
@Serializable
data class ReadingConfig(
	val entity: String,
	val name: String,
	@SerialName("warn_below") val warnBelow: Double? = null,
	@SerialName("warn_above") val warnAbove: Double? = null,
	/** One of [ReadingIcon], or none: then the name says what it is. */
	val icon: String? = null,
)

/** A chart by the hour, opened from the menu. */
@Serializable
data class ChartConfig(
	val entity: String,
	val name: String,
)

data class Reading(
	val name: String,
	val value: String,
	val unit: String?,
	val warning: Boolean,
	val icon: String? = null,
	/** From 0 to 1, for a percentage: a level bar, or the battery's fill. */
	val level: Float? = null,
)

data class HourValue(
	val start: Instant,
	val value: Double,
)

data class ChartData(
	val name: String,
	val unit: String?,
	val hours: List<HourValue>,
)

/**
 * What the panel shows of other entities, read from a `subscribe_entities` event: it knows nothing of cars or electricity, only
 * of values and of values by the hour.
 */
object EntityReadings {
	/** The readings whose entity has a state, in their order. */
	fun readings(
		configs: List<ReadingConfig>,
		event: JsonObject,
	): List<Reading> =
		configs.mapNotNull { config ->
			val entity = entity(event, config.entity) ?: return@mapNotNull null
			val state = entity.state ?: return@mapNotNull null
			val number = state.toDoubleOrNull()
			val warning =
				number != null &&
					((config.warnBelow != null && number < config.warnBelow) || (config.warnAbove != null && number > config.warnAbove))
			val unit = entity.attribute("unit_of_measurement")
			val level = if (unit == "%" && number != null) (number / 100).toFloat().coerceIn(0f, 1f) else null
			Reading(config.name, number?.let(::format) ?: state, unit, warning, config.icon, level)
		}

	/**
	 * The values by the hour in `raw_today` and `raw_tomorrow`, lists of `start` and `value`: the format of Nord Pool, ENTSO-e
	 * and EV Smart Charging. Nothing if the entity has none.
	 */
	fun chart(
		config: ChartConfig,
		event: JsonObject,
	): ChartData? {
		val entity = entity(event, config.entity) ?: return null
		val hours =
			listOf("raw_today", "raw_tomorrow")
				.flatMap { (entity.attributes?.get(it) as? JsonArray).orEmpty() }
				.mapNotNull { item ->
					val hour = item as? JsonObject ?: return@mapNotNull null
					val start = hour["start"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
					val value = hour["value"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
					try {
						HourValue(OffsetDateTime.parse(start).toInstant(), value)
					} catch (_: DateTimeParseException) {
						null
					}
				}.sortedBy { it.start }
		if (hours.isEmpty()) return null
		return ChartData(config.name, entity.attribute("unit_of_measurement"), hours)
	}

	/** How long each value lasts: an hour, or the quarters some tariffs use. */
	fun step(hours: List<HourValue>): Long =
		hours
			.zipWithNext { a, b -> b.start.epochSecond - a.start.epochSecond }
			.filter { it > 0 }
			.minOrNull() ?: HOUR_SECONDS

	/** From the value running now, for the next [span] seconds. */
	fun window(
		hours: List<HourValue>,
		now: Instant,
		span: Long = WINDOW_SECONDS,
	): List<HourValue> {
		val step = step(hours)
		return hours.filter { it.start.plusSeconds(step) > now && it.start < now.plusSeconds(span) }
	}

	/** The lowest values of the window, for [span] seconds in all, which the chart picks out. */
	fun cheapest(
		hours: List<HourValue>,
		span: Long = CHEAPEST_SECONDS,
	): Set<Instant> =
		hours
			.sortedBy { it.value }
			.take(maxOf(1, (span / step(hours)).toInt()))
			.map { it.start }
			.toSet()

	/** A whole number without decimals, the rest with up to two: "73.0" is 73. */
	fun format(value: Double): String =
		if (abs(value - Math.round(value)) < 1e-9) {
			Math.round(value).toString()
		} else {
			"%.2f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
		}

	private class Entity(
		val state: String?,
		val attributes: JsonObject?,
	) {
		fun attribute(name: String): String? = attributes?.get(name)?.jsonPrimitive?.contentOrNull
	}

	private fun entity(
		event: JsonObject,
		id: String,
	): Entity? {
		val entity =
			(event["event"] as? JsonObject)
				?.let { it["a"] as? JsonObject }
				?.let { it[id] as? JsonObject }
				?: return null
		val state = entity["s"]?.jsonPrimitive?.contentOrNull?.takeUnless { it == "unavailable" || it == "unknown" }
		return Entity(state, entity["a"] as? JsonObject)
	}

	private const val HOUR_SECONDS = 3600L
	const val WINDOW_SECONDS = 24 * HOUR_SECONDS
	const val CHEAPEST_SECONDS = 6 * HOUR_SECONDS
}
