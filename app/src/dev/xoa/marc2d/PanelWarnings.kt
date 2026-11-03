package dev.xoa.marc2d

/** The panel's status texts. */
object PanelWarnings {
	fun haStatus(status: HaStatus): String =
		when (status) {
			HaStatus.NotConfigured -> "Not connected to Home Assistant yet"
			HaStatus.Connecting -> "Connecting to Home Assistant…"
			HaStatus.Connected -> "Connected to Home Assistant"
			HaStatus.AuthFailed -> "Home Assistant rejected this panel: add it again"
			HaStatus.MissingScript -> "script.marc2d_config is missing in Home Assistant"
			is HaStatus.InvalidConfig -> "script.marc2d_config: ${status.reason}"
			is HaStatus.Disconnected -> "No connection to Home Assistant (${status.reason})"
		}

	/** The warning on the main screen, or nothing if everything works. */
	fun warning(
		ha: HaStatus,
		intercoms: Map<String, IntercomStatus>,
		micGranted: Boolean,
		mqtt: MqttStatus = MqttStatus.Off,
	): String? {
		val lines =
			buildList {
				if (!micGranted) add("Microphone permission is missing")
				if (ha != HaStatus.Connected && ha != HaStatus.Connecting) add(haStatus(ha))
				if (mqtt == MqttStatus.Connecting) add("No connection to MQTT")
				intercoms
					.filterValues { it != IntercomStatus.Registered }
					.keys
					.forEach { add("$it: not registered") }
			}
		return lines.joinToString("\n").ifEmpty { null }
	}
}
