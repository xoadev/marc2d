package dev.xoa.marc2d

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.seconds

private val AUTO_CLOSE = 10.seconds
private val CONTACTS_AUTO_CLOSE = 30.seconds
private val MENU_BUTTON = 160.dp
private val CONTACT_WIDTH = 240.dp

/** Opens by tapping the main screen, and closes by itself so as not to leave anything fixed on the OLED. */
@Composable
fun PanelMenu(
	onCameras: (() -> Unit)?,
	onPhone: (() -> Unit)?,
	onSettings: () -> Unit,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
) {
	AutoClosingOverlay(onClose, modifier, timeout = AUTO_CLOSE, contentAlignment = Alignment.Center) {
		Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
			if (onCameras != null) PixelButton(PixelIcon.Camera, "Cameras", onCameras, size = MENU_BUTTON)
			if (onPhone != null) PixelButton(PixelIcon.Talk, "Phone", onPhone, size = MENU_BUTTON)
			PixelButton(PixelIcon.Settings, "Settings", onSettings, size = MENU_BUTTON)
		}
	}
}

/** The contacts from HA, in their order: tapping one calls it over the SIM, and the call screen takes over. */
@Composable
fun ContactsScreen(
	contacts: List<Contact>,
	onCall: (Contact) -> Unit,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
) {
	AutoClosingOverlay(onClose, modifier, timeout = CONTACTS_AUTO_CLOSE) {
		LazyVerticalGrid(
			columns = GridCells.Adaptive(CONTACT_WIDTH),
			// Below the close button, which sits at the top end.
			contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 128.dp, bottom = 32.dp),
			horizontalArrangement = Arrangement.spacedBy(24.dp),
			verticalArrangement = Arrangement.spacedBy(24.dp),
		) {
			items(contacts) { contact -> PixelTextButton(contact.name, onClick = { onCall(contact) }) }
		}
	}
}
