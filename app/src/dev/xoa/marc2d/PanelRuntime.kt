package dev.xoa.marc2d

import android.content.Context
import android.os.Build
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Conecta las piezas del panel. Vive lo que el proceso; el servicio solo lo mantiene en marcha. */
class PanelRuntime(
	context: Context,
) {
	val store = PanelStore(context)
	val phone by lazy { IntercomPhone(context) }

	private val _haStatus = MutableStateFlow<HaStatus>(HaStatus.NotConfigured)
	val haStatus: StateFlow<HaStatus> = _haStatus.asStateFlow()

	private val _weather = MutableStateFlow<WeatherReport?>(null)
	val weather: StateFlow<WeatherReport?> = _weather.asStateFlow()

	private val _haMood = MutableStateFlow(HaMood.Normal)
	val haMood: StateFlow<HaMood> = _haMood.asStateFlow()

	private val _dark = MutableStateFlow(false)
	val dark: StateFlow<Boolean> = _dark.asStateFlow()

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val ha = HaClient()
	private val siren = PanelSiren(context) { on -> publishSiren(on) }
	private val readings = PanelReadings(context)
	val mqtt = PanelMqtt(scope, Build.MODEL, siren::set, { intercom, payload -> phone.doorbell(intercom, payload) }, ::setHaMood)
	private var started = false

	fun start() {
		if (started) return
		started = true

		scope.launch {
			store.config.collect { phone.apply(it) }
		}
		scope.launch {
			combine(store.config, store.connection) { config, connection -> config.mqtt to connection?.panel }
				.collect { (config, panel) -> mqtt.apply(config.takeIf { panel != null }, panel.orEmpty()) }
		}
		phone.onDoorbellAnswered = mqtt::doorbellAnswered
		publishSiren(false)
		mqtt.publish(PanelTopics::mood, HaMood.Normal.label)
		scope.launch { publishBattery() }
		scope.launch { publishLight() }
		scope.launch {
			store.connection.collectLatest { connection ->
				if (connection == null) {
					_haStatus.value = HaStatus.NotConfigured
				} else {
					keepConnected(connection)
				}
			}
		}
	}

	private fun setHaMood(label: String) {
		val mood = HaMood.fromLabel(label) ?: return
		_haMood.value = mood
		mqtt.publish(PanelTopics::mood, mood.label)
	}

	private fun publishSiren(on: Boolean) {
		mqtt.publish(PanelTopics::siren, if (on) PanelTopics.ON else PanelTopics.OFF)
	}

	private suspend fun publishBattery() {
		while (true) {
			val battery = readings.battery()
			if (battery != null) {
				mqtt.publish(PanelTopics::battery, battery.level.toString())
				mqtt.publish(PanelTopics::temperature, battery.temperature.toString())
				mqtt.publish(PanelTopics::charging, if (battery.charging) PanelTopics.ON else PanelTopics.OFF)
			}
			delay(BATTERY_INTERVAL)
		}
	}

	private suspend fun publishLight() {
		var last: Float? = null
		readings.light().collect { lux ->
			_dark.value = lux < DARK_LUX
			if (PanelReadings.isSignificant(last, lux)) {
				last = lux
				mqtt.publish(PanelTopics::light, lux.roundToInt().toString())
			}
		}
	}

	private suspend fun keepConnected(connection: HaConnection) {
		var wait = MIN_RETRY
		// Solo al principio: durante los reintentos se mantiene el aviso de la última desconexión.
		_haStatus.value = HaStatus.Connecting
		while (true) {
			try {
				ha.run(
					connection = connection,
					onStatus = { status ->
						_haStatus.value = status
						wait = MIN_RETRY
					},
					onConfig = store::saveConfig,
					onWeather = { _weather.value = it },
				)
			} catch (e: CancellationException) {
				throw e
			} catch (_: HaAuthException) {
				// Reintentar no arregla un token malo: se espera a que cambie la configuración.
				_haStatus.value = HaStatus.AuthFailed
				return
			} catch (e: Exception) {
				_haStatus.value = HaStatus.Disconnected(e::class.simpleName ?: "error")
			}
			delay(wait)
			wait = min(wait.inWholeSeconds * 2, MAX_RETRY.inWholeSeconds).seconds
		}
	}

	private companion object {
		const val DARK_LUX = 5f
		val BATTERY_INTERVAL = 1.minutes
		val MIN_RETRY = 5.seconds
		val MAX_RETRY = 60.seconds
	}
}
