package dev.xoa.marc2d

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The Home Assistant WebSocket API messages the panel uses. */
object HaProtocol {
	private const val CONFIG_SCRIPT = "marc2d_config"
	private val json = Json { ignoreUnknownKeys = true }

	sealed interface ConfigResult {
		data class Ok(
			val config: PanelConfig,
		) : ConfigResult

		data object MissingScript : ConfigResult

		data class Invalid(
			val reason: String,
		) : ConfigResult
	}

	fun webSocketUrl(haUrl: String): String {
		val base = haUrl.trim().trimEnd('/')
		val url =
			when {
				base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
				base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
				else -> "ws://$base"
			}
		return "$url/api/websocket"
	}

	fun auth(token: String): String =
		buildJsonObject {
			put("type", "auth")
			put("access_token", token)
		}.toString()

	fun configRequest(
		id: Int,
		panel: String,
	): String =
		buildJsonObject {
			put("id", id)
			put("type", "call_service")
			put("domain", "script")
			put("service", CONFIG_SCRIPT)
			putJsonObject("service_data") { put("panel", panel) }
			put("return_response", true)
		}.toString()

	fun entityState(
		id: Int,
		entity: String,
	): String =
		buildJsonObject {
			put("id", id)
			put("type", "subscribe_entities")
			putJsonArray("entity_ids") { add(entity) }
		}.toString()

	fun unsubscribe(
		id: Int,
		subscription: Int,
	): String =
		buildJsonObject {
			put("id", id)
			put("type", "unsubscribe_events")
			put("subscription", subscription)
		}.toString()

	fun hourlyForecast(
		id: Int,
		entity: String,
	): String =
		buildJsonObject {
			put("id", id)
			put("type", "call_service")
			put("domain", "weather")
			put("service", "get_forecasts")
			putJsonObject("target") { put("entity_id", entity) }
			putJsonObject("service_data") { put("type", "hourly") }
			put("return_response", true)
		}.toString()

	fun isSuccess(message: JsonObject): Boolean = message["success"]?.jsonPrimitive?.booleanOrNull == true

	/** No state means no report. No forecast still gives one: at least the current weather is shown. */
	fun weather(
		entity: String,
		state: JsonObject,
		forecast: JsonObject,
		now: Instant,
	): WeatherReport? {
		val current =
			(state["event"] as? JsonObject)
				?.let { it["a"] as? JsonObject }
				?.let { it[entity] as? JsonObject }
				?: return null
		val condition = current["s"]?.jsonPrimitive?.contentOrNull ?: return null
		val temperature =
			(current["a"] as? JsonObject)
				?.get("temperature")
				?.jsonPrimitive
				?.doubleOrNull
		return WeatherReport(condition, temperature, hours(entity, forecast), now)
	}

	private fun hours(
		entity: String,
		message: JsonObject,
	): List<HourForecast> {
		val list =
			(message["result"] as? JsonObject)
				?.let { it["response"] as? JsonObject }
				?.let { it[entity] as? JsonObject }
				?.let { it["forecast"] as? JsonArray }
				?: return emptyList()
		return list.mapNotNull { item ->
			val hour = item as? JsonObject ?: return@mapNotNull null
			val time = hour["datetime"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
			val condition = hour["condition"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
			val temperature = hour["temperature"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
			try {
				HourForecast(OffsetDateTime.parse(time).toInstant(), condition, temperature)
			} catch (_: DateTimeParseException) {
				null
			}
		}
	}

	fun parse(message: String): JsonObject = json.parseToJsonElement(message).jsonObject

	fun type(message: JsonObject): String? = message["type"]?.jsonPrimitive?.content

	fun id(message: JsonObject): Int? = message["id"]?.jsonPrimitive?.int

	fun configResult(message: JsonObject): ConfigResult {
		if (message["success"]?.jsonPrimitive?.booleanOrNull != true) {
			val error = message["error"]?.jsonObject
			val code = error?.get("code")?.jsonPrimitive?.content
			return if (code == "not_found" || code == "service_not_found") {
				ConfigResult.MissingScript
			} else {
				ConfigResult.Invalid(code ?: "unknown error")
			}
		}
		val response =
			message["result"]?.jsonObject?.get("response")
				?: return ConfigResult.Invalid("the script returns nothing")
		return try {
			ConfigResult.Ok(json.decodeFromJsonElement<PanelConfig>(response))
		} catch (_: SerializationException) {
			// The exception's message could include values from the script, such as passwords.
			ConfigResult.Invalid("the script response does not have the expected format")
		} catch (_: IllegalArgumentException) {
			ConfigResult.Invalid("the script response does not have the expected format")
		}
	}
}
