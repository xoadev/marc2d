package dev.xoa.marc2d

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

private val AUTO_CLOSE = 10.seconds
private val MENU_BUTTON = 160.dp

/** Se abre tocando la pantalla principal, y se cierra solo para no dejar nada fijo en el OLED. */
@Composable
fun PanelMenu(
	onCameras: (() -> Unit)?,
	onSettings: () -> Unit,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val close by rememberUpdatedState(onClose)
	LaunchedEffect(Unit) {
		delay(AUTO_CLOSE)
		close()
	}
	Box(
		modifier =
			modifier
				.fillMaxSize()
				.background(Color.Black)
				.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
		contentAlignment = Alignment.Center,
	) {
		PixelButton(
			icon = PixelIcon.Close,
			size = CLOSE_BUTTON,
			description = "Cerrar",
			onClick = onClose,
			modifier =
				Modifier
					.align(Alignment.TopEnd)
					.padding(24.dp),
		)
		Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
			if (onCameras != null) PixelButton(PixelIcon.Camera, "Cámaras", onCameras, size = MENU_BUTTON)
			PixelButton(PixelIcon.Settings, "Ajustes", onSettings, size = MENU_BUTTON)
		}
	}
}
