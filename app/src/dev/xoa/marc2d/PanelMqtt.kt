package dev.xoa.marc2d

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class MqttStatus { Off, Connecting, Connected }

/** Da de alta el panel en Home Assistant por MQTT, publica sus lecturas y recibe sus órdenes. */
class PanelMqtt(
	private val scope: CoroutineScope,
	private val model: String,
	private val onSiren: (Boolean) -> Unit,
	private val onDoorbell: (intercom: String, payload: String) -> Unit,
	private val onMood: (String) -> Unit,
	private val client: MqttClient = SocketMqttClient(),
) {
	private val _status = MutableStateFlow(MqttStatus.Off)
	val status: StateFlow<MqttStatus> = _status.asStateFlow()

	// Se repiten al reconectar, y guardan lo que se lee antes de tener conexión.
	private val values = ConcurrentHashMap<(PanelTopics) -> String, String>()
	private var applied: Pair<MqttConfig, String>? = null
	private var job: Job? = null

	@Volatile
	private var live: Pair<MqttSession, PanelTopics>? = null

	fun apply(
		config: MqttConfig?,
		panel: String,
	) {
		val next = config?.let { it to panel }
		if (next == applied) return
		applied = next
		job?.cancel()
		live = null
		_status.value = if (config == null) MqttStatus.Off else MqttStatus.Connecting
		if (config != null) job = scope.launch { keepConnected(config, panel) }
	}

	fun publish(
		select: (PanelTopics) -> String,
		value: String,
	) {
		if (values.put(select, value) == value) return
		live?.let { (session, topics) -> session.publish(select(topics), value, retain = true) }
	}

	/** Avisa a los demás paneles de que dejen de sonar. */
	fun doorbellAnswered(intercom: String) {
		live?.first?.publish(PanelTopics.DOORBELL_PREFIX + intercom, PanelTopics.ANSWERED, retain = false)
	}

	private suspend fun keepConnected(
		config: MqttConfig,
		panel: String,
	) {
		val topics = PanelTopics(panel)
		val options =
			MqttOptions(
				server = config.server,
				port = config.port,
				clientId = "marc2d-${topics.id}",
				username = config.username,
				password = config.password,
				will = MqttWill(topics.availability, PanelTopics.OFFLINE),
			)
		var wait = MIN_RETRY_SECONDS
		while (true) {
			try {
				client.run(
					options = options,
					onConnected = { session ->
						wait = MIN_RETRY_SECONDS
						announce(session, topics, panel)
						live = session to topics
						_status.value = MqttStatus.Connected
					},
					onMessage = { topic, payload ->
						// Llega por el hilo del cliente, y estas órdenes se atienden desde el del panel.
						if (topic == topics.sirenCommand) {
							scope.launch { onSiren(payload == PanelTopics.ON) }
						} else if (topic == topics.moodCommand) {
							scope.launch { onMood(payload) }
						} else if (topic.startsWith(PanelTopics.DOORBELL_PREFIX)) {
							scope.launch { onDoorbell(topic.removePrefix(PanelTopics.DOORBELL_PREFIX), payload) }
						}
					},
				)
			} catch (e: CancellationException) {
				throw e
			} catch (_: Exception) {
				// Se reintenta abajo, igual que si la conexión hubiera terminado sin error.
			}
			live = null
			_status.value = MqttStatus.Connecting
			delay(wait.seconds)
			wait = min(wait * 2, MAX_RETRY_SECONDS)
		}
	}

	private fun announce(
		session: MqttSession,
		topics: PanelTopics,
		panel: String,
	) {
		session.subscribe(topics.sirenCommand)
		session.subscribe(topics.moodCommand)
		session.subscribe(PanelTopics.DOORBELL_PREFIX + "+")
		session.publish(topics.discovery, PanelDiscovery.payload(topics, panel, model), retain = true)
		session.publish(topics.availability, PanelTopics.ONLINE, retain = true)
		values.forEach { (select, value) -> session.publish(select(topics), value, retain = true) }
	}

	private companion object {
		const val MIN_RETRY_SECONDS = 5L
		const val MAX_RETRY_SECONDS = 60L
	}
}
