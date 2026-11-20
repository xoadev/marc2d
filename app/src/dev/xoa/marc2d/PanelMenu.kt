package dev.xoa.marc2d

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Duration.Companion.seconds

private val AUTO_CLOSE = 10.seconds
private val MENU_BUTTON = 160.dp

/** Opens by tapping the main screen, and closes by itself so as not to leave anything fixed on the OLED. */
@Composable
fun PanelMenu(
	onCameras: (() -> Unit)?,
	charts: List<String>,
	onChart: (String) -> Unit,
	onSettings: () -> Unit,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
) {
	AutoClosingOverlay(onClose, modifier, timeout = AUTO_CLOSE, contentAlignment = Alignment.Center) {
		Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
			if (onCameras != null) PixelButton(PixelIcon.Camera, "Cameras", onCameras, size = MENU_BUTTON)
			// A chart is not clear from its icon alone: its name goes under it.
			charts.forEach { name ->
				Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
					PixelButton(PixelIcon.Chart, name, { onChart(name) }, size = MENU_BUTTON)
					Text(name, color = PixelGray, fontSize = 20.sp)
				}
			}
			PixelButton(PixelIcon.Settings, "Settings", onSettings, size = MENU_BUTTON)
		}
	}
}
