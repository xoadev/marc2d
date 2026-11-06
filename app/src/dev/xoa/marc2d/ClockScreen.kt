package dev.xoa.marc2d

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay

// Black background and dimmed white: the screens are OLED and stay on for many hours.
private val ClockColor = Color(0xFFD8D8D8)
private val DateColor = Color(0xFFA0A0A0)
private val WarningColor = Color(0xFFE0A030)
private const val MAX_CLOCK_HEIGHT = 0.5f
private val WEATHER_LIST_WIDTH = 160.dp
private const val DROID_WIDTH = 0.1f

@Composable
fun ClockScreen(
	warning: String?,
	weather: WeatherReport?,
	droid: DroidMood,
	onOpenMenu: () -> Unit,
	onFixPermissions: (() -> Unit)?,
	modifier: Modifier = Modifier,
) {
	// The order of day and month depends on the language: the device decides it.
	val locale = Locale.getDefault()
	var now by remember { mutableStateOf(LocalDateTime.now()) }
	LaunchedEffect(Unit) {
		while (true) {
			delay(ClockFormat.millisToNextMinute(LocalDateTime.now()))
			now = LocalDateTime.now()
		}
	}
	val (offsetX, offsetY) = ClockFormat.burnInOffset(now)

	BoxWithConstraints(
		modifier =
			modifier
				.fillMaxSize()
				.background(Color.Black)
				.pointerInput(Unit) { detectTapGestures(onTap = { onOpenMenu() }) },
	) {
		val clockMaxHeight = maxHeight * MAX_CLOCK_HEIGHT
		val landscape = maxWidth > maxHeight
		val instant = now.atZone(ZoneId.systemDefault()).toInstant()
		val visibleWeather = weather?.takeIf { it.isFresh(instant) }
		Column(
			modifier =
				Modifier
					.align(Alignment.Center)
					.fillMaxWidth()
					.offset(offsetX.dp, offsetY.dp)
					// In landscape the forecast goes in a list to the right of the time.
					.padding(start = 24.dp, end = if (landscape && visibleWeather != null) WEATHER_LIST_WIDTH else 24.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			BasicText(
				text = ClockFormat.time(now),
				maxLines = 1,
				autoSize = TextAutoSize.StepBased(minFontSize = 48.sp, maxFontSize = 320.sp),
				// In landscape it is the height, not the width, that limits the size of the time.
				modifier = Modifier.heightIn(max = clockMaxHeight),
				style =
					TextStyle(
						color = ClockColor,
						fontWeight = FontWeight.Light,
						// Fixed-width digits: the time does not jump around when the minute changes.
						fontFeatureSettings = "tnum",
						textAlign = TextAlign.Center,
					),
			)
			Text(
				text = ClockFormat.date(now, locale, DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")),
				color = DateColor,
				fontSize = 28.sp,
				textAlign = TextAlign.Center,
			)
		}
		if (visibleWeather != null) {
			WeatherPanel(
				report = visibleWeather,
				now = instant,
				asList = landscape,
				modifier =
					Modifier
						.align(Alignment.TopEnd)
						.offset(offsetX.dp, offsetY.dp)
						.padding(16.dp),
			)
		}
		DroidSprite(
			mood = droid,
			size = maxWidth * DROID_WIDTH,
			modifier = Modifier.offset(offsetX.dp, offsetY.dp),
		)
		if (onFixPermissions != null) {
			PixelButton(
				icon = PixelIcon.Warning,
				description = "Grant the missing permissions",
				onClick = onFixPermissions,
				size = CLOSE_BUTTON,
				modifier =
					Modifier
						.align(Alignment.TopStart)
						.padding(16.dp),
			)
		}
		if (warning != null) {
			Text(
				text = warning,
				color = WarningColor,
				fontSize = 16.sp,
				textAlign = TextAlign.Center,
				modifier =
					Modifier
						.align(Alignment.BottomCenter)
						.padding(24.dp),
			)
		}
	}
}
