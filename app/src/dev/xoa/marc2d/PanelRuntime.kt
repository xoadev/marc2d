package dev.xoa.marc2d

import android.content.Context
import android.os.SystemClock
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/** A window takes 0 to 1: below 0 it would silently go back to the system's brightness. */
internal fun windowBrightness(percent: Double): Float = (percent / 100).toFloat().coerceIn(0f, 1f)

/** Connects the panel's pieces. It lives as long as the process; the service only keeps it running. */
class PanelRuntime(
	private val context: Context,
) {
	val store = PanelStore(context)
	val phone by lazy { IntercomPhone(context) }
	val line = PhoneLine(context)

	private val _haStatus = MutableStateFlow<HaStatus>(HaStatus.NotConfigured)
	val haStatus: StateFlow<HaStatus> = _haStatus.asStateFlow()

	/** As HA last rendered them: they stay while the connection is down, and each hides by itself once it expires. */
	private val _widgets = MutableStateFlow<List<Widget>>(emptyList())
	val widgets: StateFlow<List<Widget>> = _widgets.asStateFlow()

	/** Where to download the screensaver's photos: HA's are paths, signed for this panel. Empty while it is off. */
	private val _photos = MutableStateFlow<List<String>>(emptyList())
	val photos: StateFlow<List<String>> = _photos.asStateFlow()

	/** When someone last touched the screen. Here and not in the activity: turning the panel recreates that, and the wait
	 * for the photos goes on. */
	val lastTouch = MutableStateFlow(SystemClock.elapsedRealtime())

	/** Here and not in the screen: it remembers where the pass was when someone touched the panel. */
	val photoPlaylist = PhotoPlaylist()

	/** Here too, so each photo is downloaded only once to learn its shape. */
	val photoShapes = PhotoShapes()

	private val _haMood = MutableStateFlow(HaMood.Normal)
	val haMood: StateFlow<HaMood> = _haMood.asStateFlow()

	private val _brightness = MutableStateFlow<Float?>(null)
	val brightness: StateFlow<Float?> = _brightness.asStateFlow()

	private val _listening = MutableStateFlow(false)
	val listening: StateFlow<Boolean> = _listening.asStateFlow()

	private val assistActive = MutableStateFlow(false)
	private val speaking = MutableStateFlow(false)
	private var assistEnd: Job? = null

	/** From the end of what was said until the answer sounds: the wait that would otherwise look like nothing happening. */
	val thinking: StateFlow<Boolean> by lazy {
		combine(assistActive, _listening, speaking) { active, listening, playing -> active && !listening && !playing }
			.stateIn(scope, SharingStarted.Eagerly, false)
	}

	private val _cameraOn = MutableStateFlow(false)
	val cameraOn: StateFlow<Boolean> = _cameraOn.asStateFlow()

	private val _cameraSound = MutableStateFlow(false)
	val cameraSound: StateFlow<Boolean> = _cameraSound.asStateFlow()

	private val _dark = MutableStateFlow(false)
	val dark: StateFlow<Boolean> = _dark.asStateFlow()

	private val _enrollmentMessage = MutableStateFlow<String?>(null)
	val enrollmentMessage: StateFlow<String?> = _enrollmentMessage.asStateFlow()

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private var enrolling: Job? = null
	private var updating: Job? = null
	private val ha =
		HaClient().apply {
			onAssistTiming = { Log.i(TAG, it) }
			onPhotosProblem = { Log.w(TAG, "Could not get the screensaver's photos from Home Assistant, keeping the last ones: $it") }
			onAssistActive = ::assistChanged
		}
	private val enrollment = EnrollmentClient()
	private val siren = PanelSiren(context) { on -> link.report(PanelLink.SIREN, JsonPrimitive(on)) }
	private val readings = PanelReadings(context)
	private val link = PanelLink()
	private val camera = PanelCamera(context)
	private val presence = LightPresence()
	private val updater = PanelUpdater(context)
	private val speaker =
		PanelSpeaker(
			context,
			scope,
			{ playing ->
				speaking.value = playing
				if (playing) assistActive.value = false
				link.report(PanelLink.PLAYING, JsonPrimitive(playing))
			},
			{ link.report(PanelLink.VOLUME, JsonPrimitive(it)) },
		)
	private val voice = VoiceListener(VoiceModel(context.noBackupFilesDir), link::requestAssist) { _listening.value = it }
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

		scope.launch { store.config.collect(phone::apply) }
		scope.launch { store.config.collect { line.contacts = it.contacts } }
		phone.onDoorbellAnswered = link::answer
		scope.launch { keepListening() }
		scope.launch { publishCamera() }
		scope.launch { cameraOn.collect { presence.ignoreChanges(SystemClock.elapsedRealtime()) } }
		link.report(PanelLink.SIREN, JsonPrimitive(false))
		readings.appVersion()?.let { link.report(PanelLink.APP_VERSION, JsonPrimitive(it)) }
		link.report(PanelLink.UPDATING, JsonPrimitive(false))
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

	/** Only one can hold the microphone: a call, from the intercom or the phone line, or the wake phrase, which waits for it to end. */
	private suspend fun keepListening() {
		val inCall = combine(phone.call, line.call) { intercom, phoneCall -> intercom != null || phoneCall != null }
		combine(store.config.map { it.voice }, inCall, ::Pair)
			.distinctUntilChanged()
			.collectLatest { (config, inCall) ->
				// A call only pauses it: the model stays loaded for when the call ends.
				if (config == null) voice.close()
				if (config == null || inCall) return@collectLatest
				while (true) {
					try {
						voice.run(config.pipeline, config.phrase)
					} catch (e: CancellationException) {
						throw e
					} catch (e: LinkageError) {
						// A native library that cannot load never will: the rest of the panel, the intercom first, goes on.
						Log.e(TAG, "Voice is off, its native library cannot load: ${e.message}")
						return@collectLatest
					} catch (e: Exception) {
						Log.w(TAG, "Voice stopped: ${e::class.simpleName}")
					}
					delay(VOICE_RETRY)
				}
			}
	}

	/** The server only listens: it is the first client that turns the camera on. */
	private suspend fun publishCamera() {
		store.config
			.map { it.rtsp }
			.distinctUntilChanged()
			.collectLatest { rtsp ->
				if (rtsp == null) {
					link.forget(PanelLink.ADDRESS)
					link.forget(PanelLink.CAMERA_ON)
					return@collectLatest
				}
				link.report(PanelLink.CAMERA_ON, JsonPrimitive(false))
				coroutineScope {
					launch { reportAddress() }
					serveCamera(rtsp)
				}
			}
	}

	/** Where to find the camera, which changes with the network. */
	private suspend fun reportAddress() {
		panelAddress(context).collect { address ->
			if (address == null) link.forget(PanelLink.ADDRESS) else link.report(PanelLink.ADDRESS, JsonPrimitive(address))
		}
	}

	private suspend fun serveCamera(rtsp: RtspConfig): Nothing {
		while (true) {
			try {
				val server =
					RtspServer(
						rtsp.password,
						camera,
						onCamera = { on ->
							_cameraOn.value = on
							link.report(PanelLink.CAMERA_ON, JsonPrimitive(on))
						},
						audio = if (rtsp.audio) PanelMicrophone(context) else null,
						onSound = { _cameraSound.value = it },
					)
				server.onProblem = { Log.w(TAG, "RTSP client: $it") }
				withContext(Dispatchers.IO) { server.run(RTSP_PORT) }
			} catch (e: CancellationException) {
				throw e
			} catch (e: IOException) {
				Log.w(TAG, "RTSP server stopped: ${e::class.simpleName}")
			}
			delay(RTSP_RETRY)
		}
	}

	/** A new version replaces the process, so success needs no answer: the new one reports again. */
	private fun update(
		url: String,
		sha256: String,
	) {
		if (updating?.isActive == true) return
		updating =
			scope.launch {
				link.report(PanelLink.UPDATING, JsonPrimitive(true))
				try {
					updater.install(url, sha256)
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Log.w(TAG, "Update failed: ${e::class.simpleName}")
					updateFailed()
				}
			}
	}

	/**
	 * The request ends a moment before its answer starts sounding, so it waits a little for it. A connection lost halfway
	 * sends no end, hence the limit.
	 */
	private fun assistChanged(active: Boolean) {
		assistEnd?.cancel()
		if (active) assistActive.value = true
		assistEnd =
			scope.launch {
				delay(if (active) MAX_ASSIST else ANSWER_GRACE)
				assistActive.value = false
			}
	}

	fun updateFailed() {
		link.report(PanelLink.UPDATING, JsonPrimitive(false))
	}

	private fun handle(command: HaProtocol.PanelEvent) {
		when (command) {
			is HaProtocol.PanelEvent.Siren -> {
				siren.set(command.on)
			}

			is HaProtocol.PanelEvent.Mood -> {
				HaMood.fromLabel(command.value)?.let { _haMood.value = it }
			}

			is HaProtocol.PanelEvent.Doorbell -> {
				phone.doorbell(command.intercom, command.payload)
			}

			is HaProtocol.PanelEvent.Play -> {
				speaker.play(HaProtocol.mediaUrl(command.url, haUrl))
			}

			HaProtocol.PanelEvent.Stop -> {
				speaker.stop()
			}

			is HaProtocol.PanelEvent.Update -> {
				update(command.url, command.sha256)
			}

			is HaProtocol.PanelEvent.Volume -> {
				speaker.setVolume(command.level)
			}

			is HaProtocol.PanelEvent.Brightness -> {
				presence.ignoreChanges(SystemClock.elapsedRealtime())
				_brightness.value = windowBrightness(command.percent)
			}

			is HaProtocol.PanelEvent.Dial -> {
				line.dial(command.number)
			}

			is HaProtocol.PanelEvent.Widgets -> {
				_widgets.value = command.widgets
			}

			is HaProtocol.PanelEvent.Config -> {}
		}
	}

	private suspend fun publishBattery() {
		val signals = PanelReadings.signalReports()
		while (true) {
			readings.wifiSignal()?.let { signal ->
				if (signals.shouldReport(signal)) link.report(PanelLink.WIFI_SIGNAL, JsonPrimitive(signal))
			}
			val battery = readings.battery()
			if (battery != null) {
				link.report(PanelLink.BATTERY, JsonPrimitive(battery.level))
				link.report(PanelLink.TEMPERATURE, JsonPrimitive(battery.temperature))
				link.report(PanelLink.CHARGING, JsonPrimitive(battery.charging))
			}
			link.report(PanelLink.SIM, JsonPrimitive(line.refreshSim()))
			delay(BATTERY_INTERVAL)
		}
	}

	private suspend fun publishLight() {
		val reports = PanelReadings.lightReports()
		// Latest: a new reading replaces the wait, which the new reading works out again.
		readings.light().collectLatest { lux ->
			_dark.value = lux < DARK_LUX
			if (reports.shouldReport(lux)) link.report(PanelLink.LIGHT, JsonPrimitive(lux.roundToInt()))
			val now = SystemClock.elapsedRealtime()
			presence.update(lux, now)
			val remaining = presence.remaining(now)
			link.report(PanelLink.PRESENCE, JsonPrimitive(remaining > 0))
			if (remaining > 0) {
				delay(remaining)
				link.report(PanelLink.PRESENCE, JsonPrimitive(false))
			}
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
					onPhotos = { photos ->
						if (photos.size != _photos.value.size) Log.i(TAG, "The screensaver has ${photos.size} photos")
						_photos.value = photos
					},
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
		const val TAG = "marc2d"
		const val DARK_LUX = 5f
		val VOICE_RETRY = 60.seconds
		val RTSP_RETRY = 30.seconds
		val MAX_ASSIST = 30.seconds
		val ANSWER_GRACE = 3.seconds
		const val ENROLL_ATTEMPTS = 3
		val ENROLL_RETRY = 2.seconds
		val BATTERY_INTERVAL = 1.minutes
		val MIN_RETRY = 5.seconds
		val MAX_RETRY = 60.seconds
	}
}
