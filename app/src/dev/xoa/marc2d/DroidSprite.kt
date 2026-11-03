package dev.xoa.marc2d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.floor
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay

private const val SPRITE_PIXELS = 16
private const val STEP = 0.012f
private const val MIN_SLEEP_MOVE = 0.15f
private const val STOP_CHANCE = 0.04f
private const val MAX_PAUSE = 6
private val STEP_TIME = 500.milliseconds
private val SLEEP_TIME = 20.minutes

private val Head = Color(0xFFD8D8D8)
private val Base = Color(0xFF707070)
private val Yellow = Color(0xFFF0C020)
private val Red = Color(0xFFE02020)
private val DimRed = Color(0xFF700000)

private val HEAD =
	listOf(
		"",
		"",
		".....wwwwww",
		"...wwwwwwwwww",
		"..wwwwwwwwwwww",
		"..wwwwwwwwwwww",
		"..wwwwwwwwwwww",
		"..wwwwwwwwwwww",
		"..wwwwwwwwwwww",
		"..wwwwwwwwwwww",
		"",
	)
private val BASES =
	listOf(
		listOf(".....gggggg", "...gggggggggg", "...g.gg..gg.g"),
		listOf(".....gggggg", "...gggggggggg", "...gg.gg..gg.g"),
		listOf(".....gggggg", "...gggggggggg", "..g.gg..gg.gg"),
	)

/**
 * The droid on the main screen. It moves in jumps so as not to burn the OLED, and when its state changes
 * it stays where it is. Asleep, every 20 minutes it walks to another spot and goes back to sleep.
 */
@Composable
fun BoxWithConstraintsScope.DroidSprite(
	mood: DroidMood,
	size: Dp,
	modifier: Modifier = Modifier,
) {
	var position by remember { mutableFloatStateOf(0.5f) }
	var direction by remember { mutableIntStateOf(1) }
	var frame by remember { mutableIntStateOf(0) }
	var moving by remember { mutableStateOf(false) }
	var blink by remember { mutableStateOf(false) }

	LaunchedEffect(mood) {
		moving = false
		when (mood) {
			DroidMood.Walking -> {
				while (true) {
					moving = true
					position += direction * STEP
					if (position !in 0f..1f) {
						position = position.coerceIn(0f, 1f)
						direction = -direction
					}
					frame++
					delay(STEP_TIME)
					if (Random.nextFloat() < STOP_CHANCE) {
						moving = false
						repeat(Random.nextInt(2, MAX_PAUSE)) {
							blink = it % 2 == 0
							delay(STEP_TIME)
						}
						blink = false
					}
				}
			}

			DroidMood.Sleeping -> {
				while (true) {
					delay(SLEEP_TIME)
					var target: Float
					do {
						target = Random.nextFloat()
					} while (abs(target - position) < MIN_SLEEP_MOVE)
					direction = if (target > position) 1 else -1
					moving = true
					while (abs(target - position) > STEP) {
						position += direction * STEP
						frame++
						delay(STEP_TIME)
					}
					moving = false
				}
			}

			DroidMood.Alarm, DroidMood.Intruder, DroidMood.Recording -> {
				while (true) {
					blink = !blink
					delay(STEP_TIME)
				}
			}

			DroidMood.Offline -> {
				blink = false
			}
		}
	}

	val track = (maxWidth - size).coerceAtLeast(0.dp)
	Canvas(
		modifier =
			modifier
				.align(Alignment.BottomStart)
				.offset(x = track * position)
				.size(size),
	) {
		// Whole-number size: with decimals, lines appear between the pixels.
		val pixel = floor(this.size.width / SPRITE_PIXELS).coerceAtLeast(1f)
		val base = if (moving) BASES[1 + frame % 2] else BASES[0]
		(HEAD + base).forEachIndexed { y, row ->
			row.forEachIndexed { x, cell ->
				if (cell != '.') dot(pixel, x, y, if (cell == 'w') Head else Base)
			}
		}
		eye(pixel, mood, direction, moving, blink)
	}
}

private fun DrawScope.dot(
	pixel: Float,
	x: Int,
	y: Int,
	color: Color,
) {
	drawRect(color, Offset(x * pixel, y * pixel), Size(pixel, pixel))
}

private fun DrawScope.block(
	pixel: Float,
	x: IntRange,
	y: IntRange,
	color: Color,
) {
	for (row in y) for (column in x) dot(pixel, column, row, color)
}

private fun DrawScope.eye(
	pixel: Float,
	mood: DroidMood,
	direction: Int,
	moving: Boolean,
	blink: Boolean,
) {
	when (mood) {
		DroidMood.Sleeping -> {
			if (moving) openEye(pixel, direction) else block(pixel, 5..10, 7..7, Color.Black)
		}

		DroidMood.Walking -> {
			if (blink) block(pixel, 6..9, 7..7, Color.Black) else openEye(pixel, if (moving) direction else 0)
		}

		DroidMood.Alarm -> {
			block(pixel, 6..9, 5..8, if (blink) Yellow else Color.Black)
			block(pixel, 7..8, 6..7, if (blink) Color.Black else Yellow)
		}

		DroidMood.Intruder -> {
			block(pixel, 4..11, 6..7, if (blink) Red else DimRed)
		}

		DroidMood.Recording -> {
			block(pixel, 5..10, 4..8, Color.Black)
			block(pixel, 7..8, 5..7, if (blink) Red else DimRed)
		}

		DroidMood.Offline -> {
			listOf(6 to 5, 9 to 5, 7 to 6, 8 to 6, 7 to 7, 8 to 7, 6 to 8, 9 to 8).forEach { (x, y) -> dot(pixel, x, y, Color.Black) }
		}
	}
}

/** The glint looks where it is going. */
private fun DrawScope.openEye(
	pixel: Float,
	direction: Int,
) {
	val shift = direction.coerceIn(-1, 1)
	block(pixel, (6 + shift)..(9 + shift), 5..8, Color.Black)
	dot(pixel, 7 + shift * 2 - (if (shift < 0) -1 else 0), 6, Head)
}
