package dev.xoa.marc2d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.roundToInt

private val TILE_ICON = 40.dp
private val LEVEL_WIDTH = 72.dp

/** The icons a reading can have, chosen in its configuration: the battery fills with its value. */
enum class ReadingIcon(
	private val icon: PixelIcon?,
) {
	Battery(null),
	Car(PixelIcon.Car),
	Thermometer(PixelIcon.Thermometer),
	Power(PixelIcon.Power),
	Water(PixelIcon.Water),
	;

	fun rows(level: Float?): List<String> = icon?.rows ?: batteryRows(level ?: 0f)

	companion object {
		fun parse(value: String?): ReadingIcon? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
	}
}

/**
 * The readings as a column of tiles, on the left as the weather is on the right: the icon and the value, and a level bar for a
 * percentage. Without an icon the name says what it is. Gray, and yellow only past a warning limit.
 */
@Composable
fun ReadingTiles(
	readings: List<Reading>,
	modifier: Modifier = Modifier,
) {
	Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
		readings.forEach { Tile(it) }
	}
}

@Composable
private fun Tile(reading: Reading) {
	val color = if (reading.warning) PixelYellow else PixelGray
	val icon = ReadingIcon.parse(reading.icon)
	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(12.dp),
		modifier = Modifier.semantics { contentDescription = "${reading.name} ${reading.value}${reading.unit.orEmpty()}" },
	) {
		if (icon != null) PixelIconCanvas(icon.rows(reading.level), color, Modifier.size(TILE_ICON)) else Text(reading.name, color = color, fontSize = 22.sp)
		Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(reading.value + unitSuffix(reading.unit), color = color, fontSize = 28.sp, maxLines = 1)
			// The battery icon already shows the level.
			if (reading.level != null && icon != ReadingIcon.Battery) LevelBar(reading.level, color)
		}
	}
}

/** "%" goes next to the number; other units after a space. */
fun unitSuffix(unit: String?): String =
	when {
		unit.isNullOrEmpty() -> ""
		unit == "%" -> unit
		else -> " $unit"
	}

/** Ten cells, in whole pixels as the droid, filled with the level. */
@Composable
private fun LevelBar(
	level: Float,
	color: Color,
) {
	Canvas(Modifier.size(LEVEL_WIDTH, 8.dp)) {
		val cells = LEVEL_CELLS
		val gap = 2.dp.toPx()
		val width = floor((size.width - gap * (cells - 1)) / cells)
		val filled = (level * cells).roundToInt()
		repeat(cells) { index ->
			val x = index * (width + gap)
			drawRect(if (index < filled) color else EmptyCell, Offset(x, 0f), Size(width, size.height))
		}
	}
}

private val EmptyCell = Color(0xFF303030)
private const val GRID = 12
private const val LEVEL_CELLS = 10

/** A battery drawn with its level: the outline, its cap and as many columns filled as it has charge. */
internal fun batteryRows(level: Float): List<String> {
	val inside = INSIDE_COLUMNS
	val filled = (level.coerceIn(0f, 1f) * inside).roundToInt()
	val fill = "#".repeat(filled) + ".".repeat(inside - filled)
	return listOf(
		"............",
		"............",
		"............",
		"##########..",
		"#" + "." * 8 + "#..",
		"#.$fill.##",
		"#.$fill.##",
		"#.$fill.##",
		"#" + "." * 8 + "#..",
		"##########..",
		"............",
		"............",
	).map { it.padEnd(GRID, '.').take(GRID) }
}

private const val INSIDE_COLUMNS = 6

private operator fun String.times(count: Int): String = repeat(count)
