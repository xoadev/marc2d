package dev.xoa.marc2d

import kotlinx.serialization.Serializable

/** La configuración del panel, que devuelve `script.marc2d_config` de Home Assistant. */
@Serializable
data class PanelConfig(
	val intercoms: List<IntercomConfig> = emptyList(),
	val weather: String? = null,
	val mqtt: MqttConfig? = null,
	val cameras: List<CameraConfig> = emptyList(),
)

@Serializable
data class CameraConfig(
	val entity: String,
	val name: String,
	/** El telefonillo al que llama Hablar, si se puede hablar con esta cámara. */
	val intercom: String? = null,
)

@Serializable
data class MqttConfig(
	val server: String,
	val port: Int = 1883,
	val username: String,
	val password: String,
) {
	override fun toString(): String = "MqttConfig(server=$server, port=$port, username=$username)"
}

@Serializable
data class IntercomConfig(
	val name: String,
	val server: String,
	val extension: String,
	val password: String,
	/** El número del propio telefonillo: se le llama al descolgar. */
	val number: String = "8001",
) {
	/** Dahua usa extensiones como `9901#1`: la `#` va escapada en la URI y literal en el Digest. */
	val sipIdentity: String
		get() = "sip:${extension.replace("#", "%23")}@$server"

	override fun toString(): String = "IntercomConfig(name=$name, server=$server, extension=$extension)"
}
