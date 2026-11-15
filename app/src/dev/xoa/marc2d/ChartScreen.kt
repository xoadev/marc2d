package dev.xoa.marc2d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.floor
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay

private val AUTO_CLOSE = 2.minutes
private val CHART_GRAY = Color(0xFF505050)
private val TEXT_GRAY = Color(0xFFA0A0A0)
private val TEXT_WHITE = Color(0xFFD8D8D8)
private const val LABEL_EVERY_HOURS = 3

/**
 * A chart by the hour, full screen: the next 24 hours as bars, the lowest ones in white and the rest in gray, with no colour, as
 * colour is kept for warnings. The current one is framed. It closes by itself, so as not to leave anything fixed on the OLED.
 */
@Composable
fun ChartScreen(
	chart: ChartData?,
	name: String,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val close by rememberUpdatedState(onClose)
	LaunchedEffect(Unit) {
		delay(AUTO_CLOSE)
		close()
	}
	val now = Instant.now()
	val hours = chart?.let { EntityReadings.window(it.hours, now) }.orEmpty()
	Box(
		modifier =
			modifier
				.fillMaxSize()
				.background(Color.Black)
				.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
	) {
		Column(Modifier.fillMaxSize().padding(24.dp)) {
			Text(name, color = TEXT_WHITE, fontSize = 28.sp)
			if (hours.isEmpty()) {
				Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
					Text("No values for the next hours", color = TEXT_GRAY, fontSize = 22.sp)
				}
			} else {
				val unit = chart?.unit.orEmpty()
				val low = hours.minOf { it.value }
				val high = hours.maxOf { it.value }
				Text(
					"${EntityReadings.format(low)} – ${EntityReadings.format(high)} $unit".trim(),
					color = TEXT_GRAY,
					fontSize = 20.sp,
				)
				Bars(hours, EntityReadings.cheapest(hours), now, Modifier.fillMaxWidth().weight(1f).padding(top = 16.dp))
			}
		}
		PixelButton(
			icon = PixelIcon.Close,
			size = CLOSE_BUTTON,
			description = "Close",
			onClick = onClose,
			modifier =
				Modifier
					.align(Alignment.TopEnd)
					.padding(24.dp),
		)
	}
}

@Composable
private fun Bars(
	hours: List<HourValue>,
	lowest: Set<Instant>,
	now: Instant,
	modifier: Modifier = Modifier,
) {
	val text = rememberTextMeasurer()
	val zone = ZoneId.systemDefault()
	val step = EntityReadings.step(hours)
	Canvas(modifier) {
		val labelHeight = 28.dp.toPx()
		val chartHeight = size.height - labelHeight
		// Bars from zero, or from the lowest value when there are negative ones.
		val floor = minOf(0.0, hours.minOf { it.value })
		val top = hours.maxOf { it.value }.takeIf { it > floor } ?: (floor + 1)
		val slot = size.width / hours.size
		// Whole pixels and a gap between bars, as the droid is drawn.
		val gap = maxOf(1f, floor(slot * 0.2f))
		hours.forEachIndexed { index, hour ->
			val height = ((hour.value - floor) / (top - floor) * chartHeight).toFloat().coerceAtLeast(2f)
			val x = floor(index * slot)
			val color = if (hour.start in lowest) TEXT_WHITE else CHART_GRAY
			drawRect(color, Offset(x, chartHeight - height), Size(slot - gap, height))
			val current = !hour.start.isAfter(now) && hour.start.plusSeconds(step).isAfter(now)
			if (current) drawRect(TEXT_WHITE, Offset(x - gap / 2, 0f), Size(slot, chartHeight), style = Stroke(width = 2.dp.toPx()))
			val local = hour.start.atZone(zone)
			if (local.minute == 0 && local.hour % LABEL_EVERY_HOURS == 0) label(text, "%02d".format(local.hour), x, chartHeight + 4.dp.toPx())
		}
	}
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(
	text: TextMeasurer,
	value: String,
	x: Float,
	y: Float,
) {
	drawText(text, value, Offset(x, y), TextStyle(color = TEXT_GRAY, fontSize = 16.sp))
}
