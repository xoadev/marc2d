package dev.xoa.marc2d

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The panel's configuration, pushed by the marc2d integration. What is turned off there does not arrive. */
@Serializable
data class PanelConfig(
	val intercoms: List<IntercomConfig> = emptyList(),
	val cameras: List<CameraConfig> = emptyList(),
	val voice: VoiceConfig? = null,
	val rtsp: RtspConfig? = null,
	val photos: PhotosConfig? = null,
	/** Empty while the phone is turned off in HA. */
	val contacts: List<Contact> = emptyList(),
) {
	companion object {
		// A version of the app may have stored fields that this one no longer has.
		private val STORED = Json { ignoreUnknownKeys = true }

		/** Nothing if a version of the app stored it differently: HA sends it again on connecting, so this must not stop the app. */
		fun fromStored(json: String): PanelConfig? =
			try {
				STORED.decodeFromString<PanelConfig>(json)
			} catch (_: IllegalArgumentException) {
				null
			}
	}
}

/** Present only if voice commands are on. Without a pipeline, HA uses its preferred one. */
@Serializable
data class VoiceConfig(
	val pipeline: String? = null,
	val phrase: String = DEFAULT_PHRASE,
) {
	companion object {
		const val DEFAULT_PHRASE = "jarvis"
	}
}

/**
 * Present only if the photo screensaver is on and has a folder. The photos themselves are asked for with `marc2d/photos`:
 * the folder stays in HA, and the panel only gets the addresses, signed for it.
 */
@Serializable
data class PhotosConfig(
	/** How long the main screen stays untouched before the photos take its place. */
	@SerialName("idle_minutes") val idleMinutes: Int = 1,
	/** How long each photo stays. */
	val seconds: Int = 30,
	val order: PhotoOrder = PhotoOrder.Shuffle,
	val fit: PhotoFit = PhotoFit.Whole,
	/** Only photos shaped like the screen: landscape ones with the panel in landscape, portrait ones in portrait. */
	@SerialName("match_orientation") val matchOrientation: Boolean = true,
	/** The time and date, small, over the photos. */
	val clock: Boolean = true,
	/** Whether the photos keep the screen on, instead of leaving it to Android's screen timeout. */
	@SerialName("keep_screen_on") val keepScreenOn: Boolean = false,
)

@Serializable
enum class PhotoOrder {
	/** Shuffled again on each pass, so that every photo shows once before any repeats. */
	@SerialName("shuffle")
	Shuffle,

	/** By name, as HA sorts them. */
	@SerialName("name")
	Name,
}

@Serializable
enum class PhotoFit {
	/** The whole photo, with black bars where its shape is not the screen's. */
	@SerialName("fit")
	Whole,

	/** Cropped to fill the screen. */
	@SerialName("fill")
	Fill,
}

/** Present only if the camera is published. */
@Serializable
data class RtspConfig(
	val password: String,
	/** Whether the stream carries the microphone. */
	val audio: Boolean = false,
) {
	override fun toString(): String = "RtspConfig"
}

/** Someone the panel calls over its SIM, and whose calls show their name. */
@Serializable
data class Contact(
	val name: String,
	val number: String,
) {
	/** The number never reaches the log. */
	override fun toString(): String = "Contact(name=$name)"
}

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
	/**
	 * An RTSP URL with its credentials: then the video, the sound and the panel's voice go over RTSP, from the moment it rings,
	 * and SIP only tells the intercom someone answered. See [RtspCall].
	 */
	val rtsp: String? = null,
) {
	/** Dahua uses extensions like `9901#1`: the `#` is escaped in the URI and literal in the Digest. */
	val sipIdentity: String
		get() = "sip:${extension.replace("#", "%23")}@$server"

	override fun toString(): String = "IntercomConfig(name=$name, server=$server, extension=$extension)"
}
