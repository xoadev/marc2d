package dev.xoa.marc2d

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Los temas MQTT de un panel. Cada lectura va en el suyo para que HA no necesite plantillas. */
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

		/** Común a todos los paneles: `marc2d/doorbell/<telefonillo>`. No se retiene: un timbre antiguo no debe sonar. */
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

/** El autodescubrimiento de HA: un solo mensaje crea el dispositivo y todas sus entidades. */
object PanelDiscovery {
	fun payload(
		topics: PanelTopics,
		panel: String,
		model: String,
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
				sensor(topics, "light", "Luz", topics.light, "illuminance", "lx")
				sensor(topics, "battery", "Batería", topics.battery, "battery", "%")
				sensor(topics, "battery_temperature", "Temperatura de la batería", topics.temperature, "temperature", "°C")
				putJsonObject("charging") {
					put("platform", "binary_sensor")
					put("unique_id", "marc2d_${topics.id}_charging")
					put("name", "Cargando")
					put("device_class", "battery_charging")
					put("state_topic", topics.charging)
				}
				putJsonObject("mood") {
					put("platform", "select")
					put("unique_id", "marc2d_${topics.id}_mood")
					put("name", "Estado")
					put("state_topic", topics.mood)
					put("command_topic", topics.moodCommand)
					// Retenido: al reiniciarse, el panel recupera la alarma que estuviera activa.
					put("retain", true)
					putJsonArray("options") { HaMood.entries.forEach { add(it.label) } }
				}
				putJsonObject("siren") {
					put("platform", "siren")
					put("unique_id", "marc2d_${topics.id}_siren")
					put("name", "Sirena")
					put("state_topic", topics.siren)
					put("command_topic", topics.sirenCommand)
				}
			}
		}.toString()

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
