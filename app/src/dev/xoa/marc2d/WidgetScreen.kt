package dev.xoa.marc2d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

/** What a widget opens, full screen. It closes by itself, so as not to leave anything fixed on the OLED. */
@Composable
fun WidgetScreen(
	tap: WidgetTap,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
) {
	AutoClosingOverlay(onClose, modifier) {
		Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(tap.title, color = PixelWhite, fontSize = 28.sp)
			when (tap.screen) {
				TapScreen.Bars -> {
					tap.subtitle?.let { Text(it, color = PixelGray, fontSize = 20.sp) }
					Bars(tap.bars, tap.current, Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp))
				}

				TapScreen.Columns -> {
					Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
						tap.headline?.let { Text(it, color = PixelGray, fontSize = 22.sp) }
						tap.value?.let { Text(it, color = PixelWhite, fontSize = 56.sp) }
					}
					// Two rows of twelve: 24 hours fit with large type.
					tap.columns.chunked(COLUMNS_PER_ROW).forEach { row ->
						Columns(row + List(COLUMNS_PER_ROW - row.size) { BLANK }, Modifier.fillMaxWidth().padding(top = 16.dp), large = true)
					}
				}
			}
		}
	}
}

private const val COLUMNS_PER_ROW = 12
private val BLANK = WidgetColumn(label = "", value = "")

/** Bars in whole pixels with a gap between them, as the droid: white when lit, gray otherwise, the current one framed. */
@Composable
private fun Bars(
	bars: List<WidgetBar>,
	current: Int?,
	modifier: Modifier = Modifier,
) {
	if (bars.isEmpty()) return
	val text = rememberTextMeasurer()
	Canvas(modifier) {
		val labelHeight = 28.dp.toPx()
		val chartHeight = size.height - labelHeight
		// From zero, or from the lowest value when there are negative ones.
		val floor = minOf(0.0, bars.minOf { it.value })
		val top = bars.maxOf { it.value }.takeIf { it > floor } ?: (floor + 1)
		val slot = size.width / bars.size
		val gap = maxOf(1f, floor(slot * 0.2f))
		bars.forEachIndexed { index, bar ->
			val height = ((bar.value - floor) / (top - floor) * chartHeight).toFloat().coerceAtLeast(2f)
			val x = floor(index * slot)
			drawRect(if (bar.lit) PixelWhite else PixelDisabled, Offset(x, chartHeight - height), Size(slot - gap, height))
			if (index == current) drawRect(PixelWhite, Offset(x - gap / 2, 0f), Size(slot, chartHeight), style = Stroke(width = 2.dp.toPx()))
			if (bar.label.isNotEmpty()) {
				drawText(text, bar.label, Offset(x, chartHeight + 4.dp.toPx()), TextStyle(color = PixelGray, fontSize = 14.sp))
			}
		}
	}
}
