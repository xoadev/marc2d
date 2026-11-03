package dev.xoa.marc2d

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId

private val PrimaryColor = Color(0xFFD8D8D8)
private val SecondaryColor = Color(0xFFA0A0A0)

/**
 * El tiempo actual y la previsión hora a hora: en columnas si hay anchura de sobra encima de la
 * hora, como en vertical, y en lista si no, como en apaisado.
 */
@Composable
fun WeatherPanel(
	report: WeatherReport,
	now: Instant,
	asList: Boolean,
	modifier: Modifier = Modifier,
) {
	val zone = ZoneId.systemDefault()
	val hours = report.upcoming(now)
	Column(
		modifier = modifier,
		horizontalAlignment = Alignment.End,
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Row(
			horizontalArrangement = Arrangement.spacedBy(8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(WeatherFormat.label(report.condition), color = SecondaryColor, fontSize = 16.sp)
			report.temperature?.let {
				Text(WeatherFormat.temperature(it), color = PrimaryColor, fontSize = 28.sp)
			}
		}
		if (asList) {
			hours.forEach { hour ->
				HourRow(
					hour = WeatherFormat.hour(hour.time, zone),
					symbol = WeatherFormat.symbol(hour.condition),
					temperature = WeatherFormat.temperature(hour.temperature),
				)
			}
		} else {
			Row {
				hours.forEach { hour ->
					HourColumn(
						hour = WeatherFormat.hour(hour.time, zone),
						symbol = WeatherFormat.symbol(hour.condition),
						temperature = WeatherFormat.temperature(hour.temperature),
					)
				}
			}
		}
	}
}

@Composable
private fun HourColumn(
	hour: String,
	symbol: String,
	temperature: String,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier.width(42.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text(hour, color = SecondaryColor, fontSize = 13.sp)
		Text(symbol, color = SecondaryColor, fontSize = 18.sp)
		Text(temperature, color = PrimaryColor, fontSize = 14.sp)
	}
}

@Composable
private fun HourRow(
	hour: String,
	symbol: String,
	temperature: String,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier,
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(hour, color = SecondaryColor, fontSize = 13.sp, modifier = Modifier.width(28.dp))
		Text(symbol, color = SecondaryColor, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.width(36.dp))
		Text(temperature, color = PrimaryColor, fontSize = 14.sp, textAlign = TextAlign.End, modifier = Modifier.width(36.dp))
	}
}
