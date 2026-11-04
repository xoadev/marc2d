package dev.xoa.marc2d

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

sealed interface HaStatus {
	data object NotConfigured : HaStatus

	data object Connecting : HaStatus

	data object Connected : HaStatus

	data object AuthFailed : HaStatus

	data object NotAdded : HaStatus

	data class InvalidConfig(
		val reason: String,
	) : HaStatus

	data class Disconnected(
		val reason: String,
	) : HaStatus
}

class HaAuthException : Exception("Home Assistant rejected the token")

/** Keeps a session with Home Assistant, which pushes the panel its configuration whenever it changes. */
class HaClient {
	private var subscription = 0
	private var pendingConfig: HaProtocol.ConfigResult? = null
	private var onCommand: (HaProtocol.PanelEvent) -> Unit = {}

	private val client =
		HttpClient(OkHttp) {
			install(WebSockets) { pingIntervalMillis = PING_INTERVAL.inWholeMilliseconds }
		}

	/** Ends with an exception when the connection is lost. */
	suspend fun run(
		connection: HaConnection,
		link: PanelLink,
		onStatus: (HaStatus) -> Unit,
		onConfig: suspend (PanelConfig) -> Unit,
		onWeather: (WeatherReport?) -> Unit,
		onCommand: (HaProtocol.PanelEvent) -> Unit,
	) {
		this.onCommand = onCommand
		client.webSocket(HaProtocol.webSocketUrl(connection.url)) {
			authenticate(connection.token)
			val ids = AtomicInteger()
			val nextId = { ids.incrementAndGet() }
			while (true) {
				val id = nextId()
				subscription = id
				pendingConfig = null
				send(Frame.Text(HaProtocol.panelSubscribe(id)))
				val answer = awaitResult(id)
				if (HaProtocol.isSuccess(answer)) {
					coroutineScope {
						launch { link.values.collect { send(Frame.Text(HaProtocol.panelState(nextId(), it))) } }
						launch { link.answered.collect { send(Frame.Text(HaProtocol.doorbellAnswered(nextId(), it))) } }
						followConfig(onStatus, onConfig, onWeather, nextId)
					}
				}
				val refusal = HaProtocol.subscribeError(answer)
				onStatus(refusal.toStatus())
				// Right after enrolling, the panel exists in HA only once the form is submitted there.
				if (refusal == HaProtocol.ConfigResult.NotAdded) delay(NOT_ADDED_RETRY) else idleUntilNextRefresh()
			}
		}
	}

	/** Never returns: applies each configuration as it arrives, and refreshes the weather meanwhile. */
	private suspend fun DefaultClientWebSocketSession.followConfig(
		onStatus: (HaStatus) -> Unit,
		onConfig: suspend (PanelConfig) -> Unit,
		onWeather: (WeatherReport?) -> Unit,
		nextId: () -> Int,
	): Nothing {
		var weather: String? = null
		while (true) {
			idleUntilNextRefresh()
			pendingConfig?.let { result ->
				pendingConfig = null
				if (result is HaProtocol.ConfigResult.Ok) {
					onConfig(result.config)
					weather = result.config.weather
				}
				onStatus(result.toStatus())
			}
			onWeather(weather?.let { fetchWeather(it, nextId) })
		}
	}

	private fun HaProtocol.ConfigResult.toStatus(): HaStatus =
		when (this) {
			is HaProtocol.ConfigResult.Ok -> HaStatus.Connected
			HaProtocol.ConfigResult.NotAdded -> HaStatus.NotAdded
			is HaProtocol.ConfigResult.Invalid -> HaStatus.InvalidConfig(reason)
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

	/** Keeps reading while it waits, to find out right away if the connection drops. A new configuration ends the wait. */
	private suspend fun DefaultClientWebSocketSession.idleUntilNextRefresh() {
		withTimeoutOrNull(REFRESH_INTERVAL) {
			while (pendingConfig == null) receiveMessage()
		}
	}

	/** Configurations may arrive while waiting for anything else: none is lost, and the wait for one ends right away. */
	private suspend fun DefaultClientWebSocketSession.receiveMessage(): JsonObject {
		while (true) {
			val frame = incoming.receive()
			if (frame is Frame.Text) {
				val message = HaProtocol.parse(frame.readText())
				if (HaProtocol.type(message) == "event" && HaProtocol.id(message) == subscription) {
					when (val event = HaProtocol.panelEvent(message)) {
						is HaProtocol.PanelEvent.Config -> pendingConfig = event.result
						null -> Unit
						else -> onCommand(event)
					}
				}
				return message
			}
		}
	}

	private companion object {
		val PING_INTERVAL = 30.seconds
		val REFRESH_INTERVAL = 10.minutes
		val NOT_ADDED_RETRY = 5.seconds
	}
}
