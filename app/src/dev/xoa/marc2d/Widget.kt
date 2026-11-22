package dev.xoa.marc2d

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A widget on the main screen, as Home Assistant rendered it: the panel only draws its pieces, and knows nothing of what
 * they mean. The pieces are in SPEC.md, under Widgets, and the integration checks them before sending them.
 */
@Serializable
data class Widget(
	val name: String = "",
	val icon: WidgetIcon? = null,
	val badge: WidgetBadge? = null,
	val tone: WidgetTone = WidgetTone.Gray,
	val lines: List<WidgetLine> = emptyList(),
	val columns: List<WidgetColumn> = emptyList(),
	val strip: WidgetStrip? = null,
	/** From 0 to 1: a level bar, or the battery's fill. */
	val level: Float? = null,
	/** What opens when it is tapped. Without it, the widget is not a button. */
	val tap: WidgetTap? = null,
	/** An ISO date and time with its offset, checked by the integration. */
	val expires: String? = null,
) {
	/** Old data is hidden: better to show nothing than a price or a forecast that no longer holds. */
	fun isShown(now: Instant): Boolean = expires == null || OffsetDateTime.parse(expires).toInstant() > now
}

@Serializable
enum class WidgetIcon(
	private val icon: PixelIcon?,
) {
	@SerialName("battery")
	Battery(null),

	@SerialName("car")
	Car(PixelIcon.Car),

	@SerialName("thermometer")
	Thermometer(PixelIcon.Thermometer),

	@SerialName("power")
	Power(PixelIcon.Power),

	@SerialName("water")
	Water(PixelIcon.Water),
	;

	/** The battery fills with the level. */
	fun rows(level: Float?): List<String> = icon?.rows ?: batteryRows(level ?: 0f)
}

@Serializable
enum class WidgetBadge {
	/** A bolt by the icon, and the level's next empty cell framed. */
	@SerialName("charging")
	Charging,
}

/** No colour but for a warning, as everywhere else. */
@Serializable
enum class WidgetTone {
	@SerialName("gray")
	Gray,

	@SerialName("white")
	White,

	@SerialName("yellow")
	Yellow,
}

@Serializable
enum class LineSize {
	@SerialName("large")
	Large,

	@SerialName("small")
	Small,
}

@Serializable
data class WidgetLine(
	val text: String,
	val size: LineSize = LineSize.Large,
	/** The widget's own, if it has none. */
	val tone: WidgetTone? = null,
)

/** A column of a forecast: [symbol] is a condition of HA's weather, such as `rainy`. */
@Serializable
data class WidgetColumn(
	val label: String,
	val symbol: String? = null,
	val value: String,
	val sub: String? = null,
)

/** Cells in white when true and in gray when not, the [current] one framed. */
@Serializable
data class WidgetStrip(
	val cells: List<Boolean>,
	val current: Int? = null,
)

@Serializable
enum class TapScreen {
	@SerialName("bars")
	Bars,

	@SerialName("columns")
	Columns,
}

/** A full screen: bars, such as the price by the hour, or columns, such as the forecast. */
@Serializable
data class WidgetTap(
	val screen: TapScreen,
	val title: String,
	val subtitle: String? = null,
	val headline: String? = null,
	val value: String? = null,
	val bars: List<WidgetBar> = emptyList(),
	val columns: List<WidgetColumn> = emptyList(),
	val current: Int? = null,
)

@Serializable
data class WidgetBar(
	val label: String = "",
	val value: Double,
	val lit: Boolean = false,
)

/** Monochrome symbols for HA's weather conditions: the U+FE0E selector stops Android from drawing them as colour emoji. */
fun weatherSymbol(condition: String?): String =
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
