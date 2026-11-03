package dev.xoa.marc2d

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SettingsGray = Color(0xFFA0A0A0)

/** The panel only needs to enroll in Home Assistant: the rest of the configuration comes from there. */
@Composable
fun SettingsScreen(
	initial: HaConnection?,
	status: String,
	intercoms: Map<String, IntercomStatus>,
	enrollmentMessage: String?,
	onScan: () -> Unit,
	onEnroll: (EnrollmentInvite) -> Unit,
	onOpenAndroidSettings: () -> Unit,
	onLeaveKiosk: (() -> Unit)?,
	onClose: (() -> Unit)?,
	modifier: Modifier = Modifier,
) {
	var url by rememberSaveable(initial) { mutableStateOf(initial?.url ?: "http://homeassistant.local:8123") }
	var code by rememberSaveable { mutableStateOf("") }

	val fieldColors =
		OutlinedTextFieldDefaults.colors(
			focusedTextColor = PixelWhite,
			unfocusedTextColor = PixelWhite,
			focusedBorderColor = PixelWhite,
			unfocusedBorderColor = PixelDisabled,
			focusedLabelColor = PixelWhite,
			unfocusedLabelColor = SettingsGray,
			cursorColor = PixelWhite,
		)
	Column(
		modifier =
			modifier
				.fillMaxSize()
				.background(Color.Black)
				.verticalScroll(rememberScrollState())
				.padding(24.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
			Text("Home Assistant connection", color = PixelWhite, fontSize = 28.sp, modifier = Modifier.weight(1f))
			if (onClose != null) PixelButton(PixelIcon.Close, "Close", onClose, size = CLOSE_BUTTON)
		}
		Text("Add this panel in Home Assistant, in the marc2d integration, and scan the code it shows.", color = SettingsGray, fontSize = 20.sp)
		PixelTextButton("Scan QR code", onScan, Modifier.fillMaxWidth())
		OutlinedTextField(
			value = url,
			onValueChange = { url = it },
			label = { Text("URL") },
			singleLine = true,
			shape = RectangleShape,
			colors = fieldColors,
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = code,
			onValueChange = { code = it },
			label = { Text("Code") },
			singleLine = true,
			shape = RectangleShape,
			colors = fieldColors,
			modifier = Modifier.fillMaxWidth(),
		)
		PixelTextButton(
			label = "Connect",
			onClick = { onEnroll(EnrollmentInvite(url.trim(), code.trim())) },
			enabled = url.isNotBlank() && code.isNotBlank(),
			modifier = Modifier.fillMaxWidth(),
		)
		if (enrollmentMessage != null) Text(enrollmentMessage, color = PixelWhite, fontSize = 20.sp)
		Text(status, color = SettingsGray, fontSize = 20.sp)
		intercoms.forEach { (name, intercom) ->
			Text("$name: ${intercom.label}", color = SettingsGray, fontSize = 20.sp)
		}
		PixelTextButton("Android settings", onOpenAndroidSettings, Modifier.fillMaxWidth())
		if (onLeaveKiosk != null) {
			PixelTextButton("Exit kiosk mode", onLeaveKiosk, Modifier.fillMaxWidth())
		}
	}
}

private val IntercomStatus.label: String
	get() =
		when (this) {
			IntercomStatus.Registering -> "registering"
			IntercomStatus.Registered -> "registered"
			IntercomStatus.Failed -> "registration error"
		}
