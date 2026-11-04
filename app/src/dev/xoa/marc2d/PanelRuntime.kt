package dev.xoa.marc2d

import android.content.Context
import android.util.Log
import java.io.IOException
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

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

	private val _brightness = MutableStateFlow<Float?>(null)
	val brightness: StateFlow<Float?> = _brightness.asStateFlow()

	private val _listening = MutableStateFlow(false)
	val listening: StateFlow<Boolean> = _listening.asStateFlow()

	private val _dark = MutableStateFlow(false)
	val dark: StateFlow<Boolean> = _dark.asStateFlow()

	private val _enrollmentMessage = MutableStateFlow<String?>(null)
	val enrollmentMessage: StateFlow<String?> = _enrollmentMessage.asStateFlow()

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private var enrolling: Job? = null
	private val ha = HaClient()
	private val enrollment = EnrollmentClient()
	private val siren = PanelSiren(context) { on -> link.report(PanelLink.SIREN, JsonPrimitive(on)) }
	private val readings = PanelReadings(context)
	private val link = PanelLink()
	private val speaker =
		PanelSpeaker(
			context,
			scope,
			{ link.report(PanelLink.PLAYING, JsonPrimitive(it)) },
			{ link.report(PanelLink.VOLUME, JsonPrimitive(it)) },
		)
	private val voice = VoiceListener(VoiceModel(context.noBackupFilesDir), link::requestAssist) { _listening.value = it }
	private var config = PanelConfig()
	private var haUrl = ""
	private var started = false

	/** Not tied to the screen: rotating recreates it, and a code that was already redeemed cannot be used again. */
	fun enroll(invite: EnrollmentInvite) {
		if (enrolling?.isActive == true) return
		enrolling =
			scope.launch {
				_enrollmentMessage.value = "Connecting…"
				_enrollmentMessage.value = enrollmentProblem(invite) { store.saveConnection(enrollWithRetries(invite)) }
			}
	}

	/** A `.local` name may fail while the network has not looked it up yet, and one person is waiting for the result. */
	private suspend fun enrollWithRetries(invite: EnrollmentInvite): HaConnection {
		repeat(ENROLL_ATTEMPTS - 1) {
			try {
				return enrollment.enroll(invite)
			} catch (_: IOException) {
				delay(ENROLL_RETRY)
			}
		}
		return enrollment.enroll(invite)
	}

	fun start() {
		if (started) return
		started = true

		scope.launch {
			store.config.collect {
				config = it
				phone.apply(it)
				if (!it.siren) siren.set(false)
				if (!it.speaker) speaker.stop()
				if (!it.sensors) PanelLink.SENSORS.forEach(link::forget)
			}
		}
		phone.onDoorbellAnswered = link::answer
		scope.launch { keepListening() }
		link.report(PanelLink.SIREN, JsonPrimitive(false))
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

	/** Only one can hold the microphone: a call, or the wake phrase, which waits for the call to end. */
	private suspend fun keepListening() {
		combine(store.config.map { it.voice }, phone.call.map { it != null }) { voice, inCall -> voice.takeUnless { inCall } }
			.distinctUntilChanged()
			.collectLatest { config ->
				if (config == null) return@collectLatest
				while (true) {
					try {
						voice.run(config.pipeline)
					} catch (e: CancellationException) {
						throw e
					} catch (e: Exception) {
						Log.w("marc2d", "Voice stopped: ${e::class.simpleName}")
					}
					delay(VOICE_RETRY)
				}
			}
	}

	private fun handle(command: HaProtocol.PanelEvent) {
		when (command) {
			is HaProtocol.PanelEvent.Siren -> if (config.siren) siren.set(command.on)
			is HaProtocol.PanelEvent.Mood -> HaMood.fromLabel(command.value)?.let { _haMood.value = it }
			is HaProtocol.PanelEvent.Doorbell -> phone.doorbell(command.intercom, command.payload)
			is HaProtocol.PanelEvent.Play -> if (config.speaker) speaker.play(HaProtocol.mediaUrl(command.url, haUrl))
			HaProtocol.PanelEvent.Stop -> speaker.stop()
			is HaProtocol.PanelEvent.Volume -> speaker.setVolume(command.level)
			is HaProtocol.PanelEvent.Brightness -> _brightness.value = (command.percent / 100).toFloat()
			is HaProtocol.PanelEvent.Config -> Unit
		}
	}

	private fun reportReading(
		key: String,
		value: JsonPrimitive,
	) {
		if (config.sensors) link.report(key, value)
	}

	private suspend fun publishBattery() {
		while (true) {
			val battery = readings.battery()
			if (battery != null) {
				reportReading("battery", JsonPrimitive(battery.level))
				reportReading("temperature", JsonPrimitive(battery.temperature))
				reportReading("charging", JsonPrimitive(battery.charging))
			}
			delay(BATTERY_INTERVAL)
		}
	}

	private suspend fun publishLight() {
		val reports = LightReports()
		readings.light().collect { lux ->
			_dark.value = lux < DARK_LUX
			if (reports.shouldReport(lux, config.sensors)) reportReading("light", JsonPrimitive(lux.roundToInt()))
		}
	}

	private suspend fun keepConnected(connection: HaConnection) {
		haUrl = connection.url
		var wait = MIN_RETRY
		// Only at the start: during retries the warning from the last disconnection stays.
		_haStatus.value = HaStatus.Connecting
		while (true) {
			try {
				ha.run(
					connection = connection,
					link = link,
					onCommand = ::handle,
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
		val VOICE_RETRY = 60.seconds
		const val ENROLL_ATTEMPTS = 3
		val ENROLL_RETRY = 2.seconds
		val BATTERY_INTERVAL = 1.minutes
		val MIN_RETRY = 5.seconds
		val MAX_RETRY = 60.seconds
	}
}
