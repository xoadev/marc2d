package dev.xoa.marc2d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.roundToInt

val WIDGET_WIDTH = 140.dp
val WIDGET_GAP = 12.dp
const val WIDGETS_PER_ROW = 2
private val WIDGET_HEIGHT = 168.dp
private val WIDGET_ICON = 36.dp
private val BADGE_ICON = 20.dp

/** The widgets, two per row, in the order HA sends them. */
@Composable
fun WidgetGrid(
	widgets: List<Widget>,
	onOpen: (Widget) -> Unit,
	modifier: Modifier = Modifier,
) {
	Column(modifier, verticalArrangement = Arrangement.spacedBy(WIDGET_GAP)) {
		widgets.chunked(WIDGETS_PER_ROW).forEach { row ->
			Row(horizontalArrangement = Arrangement.spacedBy(WIDGET_GAP)) {
				row.forEach { widget -> WidgetTile(widget, onOpen) }
			}
		}
	}
}

/** A square of pieces: icon, lines, columns, strip and level, in that order, each only if it has it. */
@Composable
private fun WidgetTile(
	widget: Widget,
	onOpen: (Widget) -> Unit,
) {
	val color = widget.tone.color()
	val tappable = widget.tap != null
	val charging = widget.badge == WidgetBadge.Charging
	val description = (listOf(widget.name) + widget.lines.map { it.text } + listOfNotNull("charging".takeIf { charging })).joinToString(", ")
	Column(
		verticalArrangement = Arrangement.SpaceBetween,
		modifier =
			Modifier
				.size(WIDGET_WIDTH, WIDGET_HEIGHT)
				.background(Color.Black)
				// A dimmer edge for what is not a button.
				.border(2.dp, if (tappable) PixelDisabled else EmptyCell)
				.then(if (tappable) Modifier.clickable(role = Role.Button) { onOpen(widget) } else Modifier)
				.semantics { contentDescription = description }
				.padding(12.dp),
	) {
		if (widget.icon != null || charging) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				widget.icon?.let { PixelIconCanvas(it.rows(widget.level), color, Modifier.size(WIDGET_ICON)) }
				if (charging) PixelIconCanvas(PixelIcon.Power.rows, PixelWhite, Modifier.size(BADGE_ICON))
			}
		}
		if (widget.lines.isNotEmpty()) {
			Column {
				widget.lines.forEach { line ->
					Text(
						line.text,
						color = (line.tone ?: widget.tone).color(),
						fontSize = if (line.size == LineSize.Small) 15.sp else 24.sp,
						maxLines = 1,
						overflow = TextOverflow.Clip,
					)
				}
			}
		}
		if (widget.columns.isNotEmpty()) Columns(widget.columns, Modifier.fillMaxWidth())
		widget.strip?.let { Strip(it, Modifier.fillMaxWidth().height(12.dp)) }
		// The battery icon already shows the level.
		if (widget.level != null && widget.icon != WidgetIcon.Battery) {
			LevelBar(widget.level, color, charging, Modifier.fillMaxWidth().height(12.dp))
		}
	}
}

/** Side by side, each with its label, symbol, value and the value under it, such as the highest and lowest temperature. */
@Composable
fun Columns(
	columns: List<WidgetColumn>,
	modifier: Modifier = Modifier,
	large: Boolean = false,
) {
	Row(modifier) {
		columns.forEach { column ->
			Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
				Text(column.label, color = PixelGray, fontSize = if (large) 16.sp else 13.sp, maxLines = 1)
				if (column.symbol != null) Text(weatherSymbol(column.symbol), color = PixelGray, fontSize = 26.sp)
				Text(column.value, color = PixelWhite, fontSize = if (large) 18.sp else 17.sp, maxLines = 1)
				column.sub?.let { Text(it, color = PixelGray, fontSize = 15.sp, maxLines = 1) }
			}
		}
	}
}

/** In whole pixels as the droid, with a gap between cells, the current one framed. */
@Composable
private fun Strip(
	strip: WidgetStrip,
	modifier: Modifier = Modifier,
) {
	Canvas(modifier) {
		val count = strip.cells.size.coerceAtLeast(1)
		val gap = 2.dp.toPx()
		val width = floor((size.width - gap * (count - 1)) / count)
		strip.cells.forEachIndexed { index, lit ->
			val x = index * (width + gap)
			drawRect(if (lit) PixelWhite else EmptyCell, Offset(x, 0f), Size(width, size.height))
			if (index == strip.current) {
				drawRect(PixelWhite, Offset(x - gap / 2, -gap), Size(width + gap, size.height + gap * 2), style = Stroke(1.dp.toPx()))
			}
		}
	}
}

/** Ten cells filled with the level. Charging, the next empty one is framed. */
@Composable
private fun LevelBar(
	level: Float,
	color: Color,
	charging: Boolean,
	modifier: Modifier = Modifier,
) {
	Canvas(modifier) {
		val gap = 2.dp.toPx()
		val width = floor((size.width - gap * (LEVEL_CELLS - 1)) / LEVEL_CELLS)
		val filled = (level.coerceIn(0f, 1f) * LEVEL_CELLS).roundToInt()
		repeat(LEVEL_CELLS) { index ->
			val x = index * (width + gap)
			when {
				index < filled -> drawRect(color, Offset(x, 0f), Size(width, size.height))
				charging && index == filled -> drawRect(PixelWhite, Offset(x, 0f), Size(width, size.height), style = Stroke(2.dp.toPx()))
				else -> drawRect(EmptyCell, Offset(x, 0f), Size(width, size.height))
			}
		}
	}
}

fun WidgetTone.color(): Color =
	when (this) {
		WidgetTone.Gray -> PixelGray
		WidgetTone.White -> PixelWhite
		WidgetTone.Yellow -> PixelYellow
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
