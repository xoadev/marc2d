package dev.xoa.marc2d

/** Los textos de estado del panel. */
object PanelWarnings {
	fun haStatus(status: HaStatus): String =
		when (status) {
			HaStatus.NotConfigured -> "Falta conectar con Home Assistant"
			HaStatus.Connecting -> "Conectando con Home Assistant…"
			HaStatus.Connected -> "Conectado a Home Assistant"
			HaStatus.AuthFailed -> "Home Assistant ha rechazado el token"
			HaStatus.MissingScript -> "Falta script.marc2d_config en Home Assistant"
			is HaStatus.InvalidConfig -> "script.marc2d_config: ${status.reason}"
			is HaStatus.Disconnected -> "Sin conexión con Home Assistant (${status.reason})"
		}

	/** El aviso de la pantalla principal, o nada si todo funciona. */
	fun warning(
		ha: HaStatus,
		intercoms: Map<String, IntercomStatus>,
		micGranted: Boolean,
		mqtt: MqttStatus = MqttStatus.Off,
	): String? {
		val lines =
			buildList {
				if (!micGranted) add("Falta el permiso del micrófono")
				if (ha != HaStatus.Connected && ha != HaStatus.Connecting) add(haStatus(ha))
				if (mqtt == MqttStatus.Connecting) add("Sin conexión con MQTT")
				intercoms
					.filterValues { it != IntercomStatus.Registered }
					.keys
					.forEach { add("$it: sin registrar") }
			}
		return lines.joinToString("\n").ifEmpty { null }
	}
}
