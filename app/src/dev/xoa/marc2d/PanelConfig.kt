package dev.xoa.marc2d

import kotlinx.serialization.Serializable

/** The panel's configuration, pushed by the marc2d integration. What is turned off there does not arrive. */
@Serializable
data class PanelConfig(
	val intercoms: List<IntercomConfig> = emptyList(),
	val weather: String? = null,
	val cameras: List<CameraConfig> = emptyList(),
	val sensors: Boolean = true,
	val siren: Boolean = true,
	val speaker: Boolean = true,
	val voice: VoiceConfig? = null,
)

/** Present only if voice commands are on. Without a pipeline, HA uses its preferred one. */
@Serializable
data class VoiceConfig(
	val pipeline: String? = null,
)

@Serializable
data class CameraConfig(
	val entity: String,
	val name: String,
	/** The intercom that Talk calls, if this camera can be talked to. */
	val intercom: String? = null,
)

@Serializable
data class IntercomConfig(
	val name: String,
	val server: String,
	val extension: String,
	val password: String,
	/** The intercom's own number: it is called on answering. */
	val number: String = "8001",
) {
	/** Dahua uses extensions like `9901#1`: the `#` is escaped in the URI and literal in the Digest. */
	val sipIdentity: String
		get() = "sip:${extension.replace("#", "%23")}@$server"

	override fun toString(): String = "IntercomConfig(name=$name, server=$server, extension=$extension)"
}
