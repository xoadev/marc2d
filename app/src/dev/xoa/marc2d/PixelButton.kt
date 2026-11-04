package dev.xoa.marc2d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

/** The X weighs less than the action buttons: leaving is not what matters on the screen. */
val CLOSE_BUTTON = 64.dp
val PixelWhite = Color(0xFFD8D8D8)
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
		Canvas(
			Modifier
				.padding(size / 5)
				.size(size * 3 / 5),
		) {
			// Whole-number size: with decimals, lines appear between the pixels.
			val pixel = floor(this.size.width / icon.rows.size).coerceAtLeast(1f)
			val start = (this.size.width - pixel * icon.rows.size) / 2
			icon.rows.forEachIndexed { y, row ->
				row.forEachIndexed { x, cell ->
					if (cell == '#') drawRect(color, Offset(start + x * pixel, start + y * pixel), Size(pixel, pixel))
				}
			}
		}
	}
}
