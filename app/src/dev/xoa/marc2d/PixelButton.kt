package dev.xoa.marc2d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay

/** The X weighs less than the action buttons: leaving is not what matters on the screen. */
val CLOSE_BUTTON = 64.dp
val PixelWhite = Color(0xFFD8D8D8)
val PixelGray = Color(0xFFA0A0A0)
val PixelYellow = Color(0xFFF0C020)
val PixelRed = Color(0xFFE02020)
val PixelDisabled = Color(0xFF505050)

/** The 12×12 icons, drawn in pixels like the droid. */
enum class PixelIcon(
	val rows: List<String>,
) {
	Talk(
		listOf(
			"..##........",
			".####.......",
			".####.......",
			".###........",
			".###........",
			"..###.......",
			"..####......",
			"...####.....",
			"....#####.##",
			".....#######",
			"......######",
			"........###.",
		),
	),
	HangUp(
		listOf(
			"............",
			"............",
			"............",
			"..########..",
			".##########.",
			"####....####",
			"####....####",
			"###......###",
			"............",
			"............",
			"............",
			"............",
		),
	),
	Close(
		listOf(
			"##........##",
			"###......###",
			".###....###.",
			"..###..###..",
			"...######...",
			"....####....",
			"....####....",
			"...######...",
			"..###..###..",
			".###....###.",
			"###......###",
			"##........##",
		),
	),
	Camera(
		listOf(
			"............",
			"..###.......",
			"############",
			"#....##....#",
			"#...####...#",
			"#..##..##..#",
			"#..##..##..#",
			"#...####...#",
			"#....##....#",
			"############",
			"............",
			"............",
		),
	),
	Warning(
		listOf(
			".....##.....",
			".....##.....",
			"....####....",
			"....#..#....",
			"...##..##...",
			"...##..##...",
			"..###..###..",
			"..###..###..",
			".##########.",
			".####..####.",
			"#####..#####",
			"############",
		),
	),
	Chart(
		listOf(
			"............",
			"..........##",
			"..........##",
			"......##..##",
			"......##..##",
			"......##..##",
			"..##..##..##",
			"..##..##..##",
			"..##..##..##",
			"..##..##..##",
			"............",
			"############",
		),
	),
	Settings(
		listOf(
			".....##.....",
			".##..##..##.",
			".##########.",
			"..###..###..",
			"..##....##..",
			"####....####",
			"####....####",
			"..##....##..",
			"..###..###..",
			".##########.",
			".##..##..##.",
			".....##.....",
		),
	),
	Car(
		listOf(
			"............",
			"............",
			"...######...",
			"..#......#..",
			".#........#.",
			"############",
			"############",
			"############",
			".##......##.",
			".##......##.",
			"............",
			"............",
		),
	),
	Thermometer(
		listOf(
			".....##.....",
			"....#..#....",
			"....#..#....",
			"....#..#....",
			"....#..#....",
			"....#..#....",
			"....####....",
			"...######...",
			"..########..",
			"..########..",
			"...######...",
			"....####....",
		),
	),
	Power(
		listOf(
			".......###..",
			"......###...",
			".....###....",
			"....###.....",
			"...#######..",
			"......###...",
			".....###....",
			"....###.....",
			"...###......",
			"..###.......",
			"............",
			"............",
		),
	),
	Water(
		listOf(
			".....##.....",
			".....##.....",
			"....####....",
			"....####....",
			"...######...",
			"..########..",
			"..########..",
			"..########..",
			"..########..",
			"...######...",
			"....####....",
			"............",
		),
	),
}

/** The side of one cell when [cells] fill the width. Whole-number: with decimals, lines appear between the pixels. */
fun DrawScope.wholePixel(cells: Int): Float = floor(size.width / cells).coerceAtLeast(1f)

/** A square pixel icon in whole pixels, at the top start or, when [centered], in the middle of the leftover space. */
@Composable
fun PixelIconCanvas(
	rows: List<String>,
	color: Color,
	modifier: Modifier = Modifier,
	centered: Boolean = false,
) {
	Canvas(modifier) {
		val pixel = wholePixel(rows.size)
		val start = if (centered) (size.width - pixel * rows.size) / 2 else 0f
		drawPixels(rows, color, pixel, Offset(start, start))
	}
}

/** Draws each '#' of [rows] as a square of [pixel], the first row's first cell at [origin]. */
fun DrawScope.drawPixels(
	rows: List<String>,
	color: Color,
	pixel: Float,
	origin: Offset = Offset.Zero,
) {
	rows.forEachIndexed { y, row ->
		row.forEachIndexed { x, cell ->
			if (cell == '#') drawRect(color, origin + Offset(x * pixel, y * pixel), Size(pixel, pixel))
		}
	}
}

/** For what an icon would not explain well, with the same shape as the other buttons. */
@Composable
fun PixelTextButton(
	label: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	enabled: Boolean = true,
) {
	val color = if (enabled) PixelWhite else PixelDisabled
	Box(
		contentAlignment = Alignment.Center,
		modifier =
			modifier
				.heightIn(min = 72.dp)
				.background(Color.Black)
				.border(3.dp, color)
				.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
				.padding(16.dp),
	) {
		Text(label, color = color, fontSize = 22.sp)
	}
}

/** Square straight-edged button with a pixel icon. The description is read by accessibility services. */
@Composable
fun PixelButton(
	icon: PixelIcon,
	description: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	color: Color = PixelWhite,
	size: Dp = 96.dp,
) {
	Box(
		contentAlignment = Alignment.Center,
		modifier =
			modifier
				.size(size)
				.background(Color.Black)
				.border(3.dp, color)
				.clickable(role = Role.Button, onClick = onClick)
				.semantics { contentDescription = description },
	) {
		PixelIconCanvas(
			icon.rows,
			color,
			Modifier
				.padding(size / 5)
				.size(size * 3 / 5),
			centered = true,
		)
	}
}

/**
 * A full-screen black layer with the X at the top end: it also closes by tapping anywhere, and by itself after [timeout], so as not
 * to leave anything fixed on the OLED.
 */
@Composable
fun AutoClosingOverlay(
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
	timeout: Duration = 2.minutes,
	closeDescription: String = "Close",
	contentAlignment: Alignment = Alignment.TopStart,
	content: @Composable BoxScope.() -> Unit,
) {
	val close by rememberUpdatedState(onClose)
	LaunchedEffect(Unit) {
		delay(timeout)
		close()
	}
	Box(
		contentAlignment = contentAlignment,
		modifier =
			modifier
				.fillMaxSize()
				.background(Color.Black)
				.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
	) {
		content()
		PixelButton(
			icon = PixelIcon.Close,
			size = CLOSE_BUTTON,
			description = closeDescription,
			onClick = onClose,
			modifier =
				Modifier
					.align(Alignment.TopEnd)
					.padding(24.dp),
		)
	}
}
