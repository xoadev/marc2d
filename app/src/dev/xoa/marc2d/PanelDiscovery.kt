package dev.xoa.marc2d

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A panel's MQTT topics. Each reading has its own so that HA needs no templates. */
class PanelTopics(
	panel: String,
) {
	val id: String = slug(panel)
	private val base = "marc2d/$id"

	val discovery = "homeassistant/device/marc2d_$id/config"
	val availability = "$base/status"
	val light = "$base/light"
	val battery = "$base/battery"
	val temperature = "$base/battery_temperature"
	val charging = "$base/charging"
	val siren = "$base/siren"
	val sirenCommand = "$base/siren/set"
	val mood = "$base/mood"
	val moodCommand = "$base/mood/set"

	companion object {
		const val ONLINE = "online"
		const val OFFLINE = "offline"
		const val ON = "ON"
		const val OFF = "OFF"

		/** Common to all panels: `marc2d/doorbell/<intercom>`. Not retained: an old doorbell press must not ring. */
		const val DOORBELL_PREFIX = "marc2d/doorbell/"
		const val RING = "RING"
		const val ANSWERED = "ANSWERED"

		fun slug(panel: String): String =
			panel
				.lowercase()
				.replace(Regex("[^a-z0-9]+"), "_")
				.trim('_')
				.ifEmpty { "panel" }
	}
}

/** HA discovery: a single message creates the device and all its entities. */
object PanelDiscovery {
	fun payload(
		topics: PanelTopics,
		panel: String,
		model: String,
		sensors: Boolean = true,
		siren: Boolean = true,
	): String =
		buildJsonObject {
			putJsonObject("device") {
				put("identifiers", "marc2d_${topics.id}")
				put("name", panel)
				put("manufacturer", "marc2d")
				put("model", model)
			}
			putJsonObject("origin") { put("name", "marc2d") }
			put("availability_topic", topics.availability)
			putJsonObject("components") {
				if (sensors) {
					sensor(topics, "light", "Light", topics.light, "illuminance", "lx")
					sensor(topics, "battery", "Battery", topics.battery, "battery", "%")
					sensor(topics, "battery_temperature", "Battery temperature", topics.temperature, "temperature", "°C")
					putJsonObject("charging") {
						put("platform", "binary_sensor")
						put("unique_id", "marc2d_${topics.id}_charging")
						put("name", "Charging")
						put("device_class", "battery_charging")
						put("state_topic", topics.charging)
					}
				} else {
					listOf("light" to "sensor", "battery" to "sensor", "battery_temperature" to "sensor", "charging" to "binary_sensor")
						.forEach { (key, platform) -> removed(key, platform) }
				}
				putJsonObject("mood") {
					put("platform", "select")
					put("unique_id", "marc2d_${topics.id}_mood")
					put("name", "State")
					put("state_topic", topics.mood)
					put("command_topic", topics.moodCommand)
					// Retained: on restart, the panel recovers whatever alarm was active.
					put("retain", true)
					putJsonArray("options") { HaMood.entries.forEach { add(it.label) } }
				}
				if (siren) {
					putJsonObject("siren") {
						put("platform", "siren")
						put("unique_id", "marc2d_${topics.id}_siren")
						put("name", "Siren")
						put("state_topic", topics.siren)
						put("command_topic", topics.sirenCommand)
					}
				} else {
					removed("siren", "siren")
				}
			}
		}.toString()

	/** A component with only its platform makes HA delete an entity that a previous discovery created. */
	private fun JsonObjectBuilder.removed(
		key: String,
		platform: String,
	) {
		putJsonObject(key) { put("platform", platform) }
	}

	private fun JsonObjectBuilder.sensor(
		topics: PanelTopics,
		key: String,
		name: String,
		topic: String,
		deviceClass: String,
		unit: String,
	) {
		putJsonObject(key) {
			put("platform", "sensor")
			put("unique_id", "marc2d_${topics.id}_$key")
			put("name", name)
			put("device_class", deviceClass)
			put("unit_of_measurement", unit)
			put("state_class", "measurement")
			put("state_topic", topic)
		}
	}
}
