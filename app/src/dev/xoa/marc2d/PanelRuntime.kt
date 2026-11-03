package dev.xoa.marc2d

import android.content.Context
import android.os.Build
import java.io.IOException
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

/** Connects the panel's pieces. It lives as long as the process; the service only keeps it running. */
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
	private val enrollment = EnrollmentClient()
	private val siren = PanelSiren(context) { on -> publishSiren(on) }
	private val readings = PanelReadings(context)
	val mqtt = PanelMqtt(scope, Build.MODEL, siren::set, { intercom, payload -> phone.doorbell(intercom, payload) }, ::setHaMood)
	private var started = false

	/** Returns what to tell the user, or nothing if the panel is now connected. */
	suspend fun enroll(invite: EnrollmentInvite): String? =
		try {
			store.saveConnection(enrollment.enroll(invite))
			null
		} catch (_: EnrollmentRejectedException) {
			"The code is not valid or has expired"
		} catch (_: IOException) {
			"Cannot reach Home Assistant at that address"
		} catch (_: IllegalArgumentException) {
			// Includes an answer that is not JSON: a typed address may be any other server.
			"That address is not a Home Assistant with marc2d"
		} catch (_: IllegalStateException) {
			"That address is not a Home Assistant with marc2d"
		}

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
				mqtt.publishReading(PanelTopics::battery, battery.level.toString())
				mqtt.publishReading(PanelTopics::temperature, battery.temperature.toString())
				mqtt.publishReading(PanelTopics::charging, if (battery.charging) PanelTopics.ON else PanelTopics.OFF)
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
				mqtt.publishReading(PanelTopics::light, lux.roundToInt().toString())
			}
		}
	}

	private suspend fun keepConnected(connection: HaConnection) {
		var wait = MIN_RETRY
		// Only at the start: during retries the warning from the last disconnection stays.
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
				// Retrying does not fix a bad token: wait for the configuration to change.
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
