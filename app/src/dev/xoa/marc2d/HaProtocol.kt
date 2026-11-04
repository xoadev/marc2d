package dev.xoa.marc2d

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The Home Assistant WebSocket API messages the panel uses. */
object HaProtocol {
	private val json = Json { ignoreUnknownKeys = true }

	sealed interface PanelEvent {
		data class Config(
			val result: ConfigResult,
		) : PanelEvent

		data class Siren(
			val on: Boolean,
		) : PanelEvent

		data class Mood(
			val value: String,
		) : PanelEvent

		data class Play(
			val url: String,
		) : PanelEvent

		data object Stop : PanelEvent

		data class Brightness(
			val percent: Double,
		) : PanelEvent

		data class Volume(
			val level: Double,
		) : PanelEvent

		data class Doorbell(
			val intercom: String,
			val payload: String,
		) : PanelEvent
	}

	sealed interface ConfigResult {
		data class Ok(
			val config: PanelConfig,
		) : ConfigResult

		data object NotAdded : ConfigResult

		data class Invalid(
			val reason: String,
		) : ConfigResult
	}

	/** HA sends its own media as a path, because only the panel knows the address it reaches HA at. Any other URL is not HA's. */
	fun mediaUrl(
		url: String,
		haUrl: String,
	): String = if (url.startsWith("/")) haUrl.trim().trimEnd('/') + url else url

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

	fun panelSubscribe(id: Int): String =
		buildJsonObject {
			put("id", id)
			put("type", "marc2d/subscribe")
		}.toString()

	fun assistRun(
		id: Int,
		pipeline: String?,
	): String =
		buildJsonObject {
			put("id", id)
			put("type", "assist_pipeline/run")
			put("start_stage", "stt")
			put("end_stage", "tts")
			putJsonObject("input") { put("sample_rate", 16_000) }
			if (pipeline != null) put("pipeline", pipeline)
		}.toString()

	/** What matters of a voice request's events. */
	sealed interface AssistEvent {
		data class Started(
			val audioHandler: Byte,
		) : AssistEvent

		data object StopListening : AssistEvent

		data class Reply(
			val url: String,
		) : AssistEvent

		data object Ended : AssistEvent
	}

	fun assistEvent(message: JsonObject): AssistEvent? {
		// Refused before starting, for example with a removed assistant: no event will follow.
		if (type(message) == "result") return if (isSuccess(message)) null else AssistEvent.Ended
		val event = message["event"] as? JsonObject ?: return null
		val data = event["data"] as? JsonObject
		return when (event["type"]?.jsonPrimitive?.contentOrNull) {
			"run-start" -> {
				(data?.get("runner_data") as? JsonObject)
					?.get("stt_binary_handler_id")
					?.jsonPrimitive
					?.intOrNull
					?.let { AssistEvent.Started(it.toByte()) }
			}

			"stt-vad-end", "stt-end", "error" -> {
				if (event["type"]?.jsonPrimitive?.content == "error") AssistEvent.Ended else AssistEvent.StopListening
			}

			"tts-end" -> {
				(data?.get("tts_output") as? JsonObject)
					?.get("url")
					?.jsonPrimitive
					?.contentOrNull
					?.let { AssistEvent.Reply(it) }
			}

			"run-end" -> {
				AssistEvent.Ended
			}

			else -> {
				null
			}
		}
	}

	fun panelState(
		id: Int,
		values: Map<String, JsonPrimitive>,
	): String =
		buildJsonObject {
			put("id", id)
			put("type", "marc2d/state")
			put("values", JsonObject(values))
		}.toString()

	fun doorbellAnswered(
		id: Int,
		intercom: String,
	): String =
		buildJsonObject {
			put("id", id)
			put("type", "marc2d/doorbell/answered")
			put("intercom", intercom)
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
		val condition =
			current["s"]
				?.jsonPrimitive
				?.contentOrNull
				?.takeUnless { it == "unavailable" || it == "unknown" }
				?: return null
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

	/** Why Home Assistant refused the subscription. */
	fun subscribeError(message: JsonObject): ConfigResult {
		val code =
			message["error"]
				?.jsonObject
				?.get("code")
				?.jsonPrimitive
				?.content
		return if (code == "not_found" || code == "unknown_command") {
			ConfigResult.NotAdded
		} else {
			ConfigResult.Invalid(code ?: "unknown error")
		}
	}

	/** Nothing for a kind that this version does not know. */
	fun panelEvent(message: JsonObject): PanelEvent? {
		val event = message["event"] as? JsonObject ?: return null

		fun text(key: String) = event[key]?.jsonPrimitive?.contentOrNull
		return when (text("kind")) {
			"config" -> {
				PanelEvent.Config(config(event))
			}

			"siren" -> {
				event["on"]?.jsonPrimitive?.booleanOrNull?.let { PanelEvent.Siren(it) }
			}

			"mood" -> {
				text("value")?.let { PanelEvent.Mood(it) }
			}

			"play" -> {
				text("url")?.let { PanelEvent.Play(it) }
			}

			"stop" -> {
				PanelEvent.Stop
			}

			"volume" -> {
				event["level"]?.jsonPrimitive?.doubleOrNull?.let { PanelEvent.Volume(it) }
			}

			"brightness" -> {
				event["level"]?.jsonPrimitive?.doubleOrNull?.let { PanelEvent.Brightness(it) }
			}

			"doorbell" -> {
				val intercom = text("intercom")
				val payload = text("payload")
				if (intercom != null && payload != null) PanelEvent.Doorbell(intercom, payload) else null
			}

			else -> {
				null
			}
		}
	}

	private fun config(event: JsonObject): ConfigResult {
		val config = event["config"] ?: return ConfigResult.Invalid("empty configuration")
		return try {
			ConfigResult.Ok(json.decodeFromJsonElement<PanelConfig>(config))
		} catch (_: SerializationException) {
			// The exception's message could include values from the configuration, such as passwords.
			ConfigResult.Invalid("the configuration does not have the expected format")
		} catch (_: IllegalArgumentException) {
			ConfigResult.Invalid("the configuration does not have the expected format")
		}
	}
}
