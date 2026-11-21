package dev.xoa.marc2d

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.seconds

private val AUTO_CLOSE = 10.seconds
private val MENU_BUTTON = 160.dp

/** Opens by tapping the main screen, and closes by itself so as not to leave anything fixed on the OLED. */
@Composable
fun PanelMenu(
	onCameras: (() -> Unit)?,
	onSettings: () -> Unit,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
) {
	AutoClosingOverlay(onClose, modifier, timeout = AUTO_CLOSE, contentAlignment = Alignment.Center) {
		Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
			if (onCameras != null) PixelButton(PixelIcon.Camera, "Cameras", onCameras, size = MENU_BUTTON)
			PixelButton(PixelIcon.Settings, "Settings", onSettings, size = MENU_BUTTON)
		}
	}
}
