package dev.xoa.marc2d

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

sealed interface HaStatus {
	data object NotConfigured : HaStatus

	data object Connecting : HaStatus

	data object Connected : HaStatus

	data object AuthFailed : HaStatus

	data object MissingScript : HaStatus

	data class InvalidConfig(
		val reason: String,
	) : HaStatus

	data class Disconnected(
		val reason: String,
	) : HaStatus
}

class HaAuthException : Exception("Home Assistant rejected the token")

/** Keeps a session with Home Assistant and asks for the configuration again from time to time. */
class HaClient {
	private val client =
		HttpClient(OkHttp) {
			install(WebSockets) { pingIntervalMillis = PING_INTERVAL.inWholeMilliseconds }
		}

	/** Ends with an exception when the connection is lost. */
	suspend fun run(
		connection: HaConnection,
		onStatus: (HaStatus) -> Unit,
		onConfig: suspend (PanelConfig) -> Unit,
		onWeather: (WeatherReport?) -> Unit,
	) {
		client.webSocket(HaProtocol.webSocketUrl(connection.url)) {
			authenticate(connection.token)
			var lastId = 0
			val nextId = { ++lastId }
			while (true) {
				val id = nextId()
				send(Frame.Text(HaProtocol.configRequest(id, connection.panel)))
				when (val result = HaProtocol.configResult(awaitResult(id))) {
					is HaProtocol.ConfigResult.Ok -> {
						onConfig(result.config)
						onStatus(HaStatus.Connected)
						onWeather(result.config.weather?.let { fetchWeather(it, nextId) })
					}

					HaProtocol.ConfigResult.MissingScript -> {
						onStatus(HaStatus.MissingScript)
					}

					is HaProtocol.ConfigResult.Invalid -> {
						onStatus(HaStatus.InvalidConfig(result.reason))
					}
				}
				idleUntilNextRefresh()
			}
		}
	}

	/** Subscribes only to receive the current state: each refresh also brings the forecast. */
	private suspend fun DefaultClientWebSocketSession.fetchWeather(
		entity: String,
		nextId: () -> Int,
	): WeatherReport? {
		val stateId = nextId()
		send(Frame.Text(HaProtocol.entityState(stateId, entity)))
		if (!HaProtocol.isSuccess(awaitResult(stateId))) return null
		val state = awaitEvent(stateId)
		send(Frame.Text(HaProtocol.unsubscribe(nextId(), stateId)))

		val forecastId = nextId()
		send(Frame.Text(HaProtocol.hourlyForecast(forecastId, entity)))
		return HaProtocol.weather(entity, state, awaitResult(forecastId), Instant.now())
	}

	private suspend fun DefaultClientWebSocketSession.authenticate(token: String) {
		while (true) {
			when (HaProtocol.type(receiveMessage())) {
				"auth_required" -> send(Frame.Text(HaProtocol.auth(token)))
				"auth_ok" -> return
				"auth_invalid" -> throw HaAuthException()
			}
		}
	}

	private suspend fun DefaultClientWebSocketSession.awaitResult(id: Int): JsonObject {
		while (true) {
			val message = receiveMessage()
			if (HaProtocol.type(message) == "result" && HaProtocol.id(message) == id) return message
		}
	}

	private suspend fun DefaultClientWebSocketSession.awaitEvent(id: Int): JsonObject {
		while (true) {
			val message = receiveMessage()
			if (HaProtocol.type(message) == "event" && HaProtocol.id(message) == id) return message
		}
	}

	/** Keeps reading while it waits, to find out right away if the connection drops. */
	private suspend fun DefaultClientWebSocketSession.idleUntilNextRefresh() {
		withTimeoutOrNull(REFRESH_INTERVAL) {
			while (true) receiveMessage()
		}
	}

	private suspend fun DefaultClientWebSocketSession.receiveMessage(): JsonObject {
		while (true) {
			val frame = incoming.receive()
			if (frame is Frame.Text) return HaProtocol.parse(frame.readText())
		}
	}

	private companion object {
		val PING_INTERVAL = 30.seconds
		val REFRESH_INTERVAL = 10.minutes
	}
}
