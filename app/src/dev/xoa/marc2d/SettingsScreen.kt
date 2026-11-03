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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SettingsGray = Color(0xFFA0A0A0)

/** El panel solo necesita conectar con Home Assistant: el resto de la configuración sale de allí. */
@Composable
fun SettingsScreen(
	initial: HaConnection?,
	status: String,
	intercoms: Map<String, IntercomStatus>,
	onSave: (HaConnection) -> Unit,
	onOpenAndroidSettings: () -> Unit,
	onLeaveKiosk: (() -> Unit)?,
	onClose: (() -> Unit)?,
	modifier: Modifier = Modifier,
) {
	var url by rememberSaveable(initial) { mutableStateOf(initial?.url ?: "http://homeassistant.local:8123") }
	var token by rememberSaveable(initial) { mutableStateOf(initial?.token ?: "") }
	var panel by rememberSaveable(initial) { mutableStateOf(initial?.panel ?: HaConnection.DEFAULT_PANEL) }

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
			Text("Conexión con Home Assistant", color = PixelWhite, fontSize = 28.sp, modifier = Modifier.weight(1f))
			if (onClose != null) PixelButton(PixelIcon.Close, "Cerrar", onClose, size = CLOSE_BUTTON)
		}
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
			value = token,
			onValueChange = { token = it },
			label = { Text("Token de larga duración") },
			singleLine = true,
			shape = RectangleShape,
			colors = fieldColors,
			visualTransformation = PasswordVisualTransformation(),
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = panel,
			onValueChange = { panel = it },
			label = { Text("Nombre del panel") },
			singleLine = true,
			shape = RectangleShape,
			colors = fieldColors,
			modifier = Modifier.fillMaxWidth(),
		)
		PixelTextButton(
			label = "Guardar",
			onClick = { onSave(HaConnection(url, token, panel)) },
			enabled = url.isNotBlank() && token.isNotBlank() && panel.isNotBlank(),
			modifier = Modifier.fillMaxWidth(),
		)
		Text(status, color = SettingsGray, fontSize = 20.sp)
		intercoms.forEach { (name, intercom) ->
			Text("$name: ${intercom.label}", color = SettingsGray, fontSize = 20.sp)
		}
		PixelTextButton("Ajustes de Android", onOpenAndroidSettings, Modifier.fillMaxWidth())
		if (onLeaveKiosk != null) {
			PixelTextButton("Salir del modo quiosco", onLeaveKiosk, Modifier.fillMaxWidth())
		}
	}
}

private val IntercomStatus.label: String
	get() =
		when (this) {
			IntercomStatus.Registering -> "registrando"
			IntercomStatus.Registered -> "registrado"
			IntercomStatus.Failed -> "error de registro"
		}
