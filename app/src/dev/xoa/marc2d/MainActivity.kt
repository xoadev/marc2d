package dev.xoa.marc2d

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
	private var kioskActive by mutableStateOf(false)
	private val kiosk by lazy { Kiosk(this) }

	private var missingPermissions by mutableStateOf(emptyList<String>())
	private val micGranted get() = Manifest.permission.RECORD_AUDIO !in missingPermissions
	private var askedByTap = false

	/** Each touch, anywhere, starts the screensaver's wait again. Only its start: a swipe would otherwise redraw everything. */
	override fun dispatchTouchEvent(event: MotionEvent): Boolean {
		if (event.actionMasked == MotionEvent.ACTION_DOWN) (application as Marc2dApp).runtime.lastTouch.value = SystemClock.elapsedRealtime()
		return super.dispatchTouchEvent(event)
	}

	private val permissions =
		registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
			refreshPermissions()
			startPanel()
			// Android stops showing the dialog after two refusals: only its settings can grant them then.
			if (askedByTap && missingPermissions.isNotEmpty()) openAppSettings()
			askedByTap = false
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// In code because `screenOrientation` in the manifest had no effect on the 3a. Follows the phone and its rotation lock.
		requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
		val runtime = (application as Marc2dApp).runtime

		setContent {
			MaterialTheme(colorScheme = darkColorScheme()) {
				Surface {
					val connection by runtime.store.connection.collectAsState(initial = null)
					val haStatus by runtime.haStatus.collectAsState()
					val intercoms by runtime.phone.status.collectAsState()
					val call by runtime.phone.call.collectAsState()
					val phoneCall by runtime.line.call.collectAsState()
					val widgets by runtime.widgets.collectAsState()
					val haMood by runtime.haMood.collectAsState()
					val dark by runtime.dark.collectAsState()
					val listening by runtime.listening.collectAsState()
					val thinking by runtime.thinking.collectAsState()
					val cameraOn by runtime.cameraOn.collectAsState()
					val cameraSound by runtime.cameraSound.collectAsState()
					val brightness by runtime.brightness.collectAsState()
					LaunchedEffect(brightness) {
						brightness?.let { level ->
							window.attributes = window.attributes.apply { screenBrightness = level }
						}
					}
					var settingsOpen by rememberSaveable { mutableStateOf(false) }
					var menuOpen by rememberSaveable { mutableStateOf(false) }
					var camerasOpen by rememberSaveable { mutableStateOf(false) }
					var contactsOpen by rememberSaveable { mutableStateOf(false) }
					// By name: the widget is looked up again, so the screen follows its new values.
					var widgetOpen by rememberSaveable { mutableStateOf<String?>(null) }
					var scanOpen by rememberSaveable { mutableStateOf(false) }
					val enrollmentMessage by runtime.enrollmentMessage.collectAsState()
					// Remembered: a new flow on each recomposition would restart the collection every time.
					val cameras by remember { runtime.store.config.map { it.cameras } }.collectAsState(initial = emptyList())
					val photosConfig by remember { runtime.store.config.map { it.photos } }.collectAsState(initial = null)
					val contacts by remember { runtime.store.config.map { it.contacts } }.collectAsState(initial = emptyList())
					val photos by runtime.photos.collectAsState()
					val lastTouch by runtime.lastTouch.collectAsState()
					// Any touch starts the wait again, and hides the photos if they were showing.
					var idle by remember { mutableStateOf(false) }
					LaunchedEffect(lastTouch, photosConfig) {
						idle = false
						val config = photosConfig ?: return@LaunchedEffect
						delay(config.idleMinutes.minutes - (SystemClock.elapsedRealtime() - lastTouch).milliseconds)
						idle = true
					}

					val warning = PanelWarnings.warning(haStatus, intercoms, micGranted)
					val droid =
						DroidMoods.resolve(
							haMood,
							hasProblem = warning != null,
							dark = dark,
							listening = listening,
							cameraOn = cameraOn,
							cameraSound = cameraSound,
							thinking = thinking,
						)
					// Only while the droid has nothing to say: an alarm, the camera, a voice command, a warning or a dark room keep
					// the clock, which shows it.
					val screensaver = idle && photos.isNotEmpty() && droid == DroidMood.Walking && photosConfig != null
					val needsSetup = haStatus == HaStatus.NotConfigured
					val activeCall = call
					val activePhoneCall = phoneCall
					val openTap = widgetOpen?.let { name -> widgets.firstOrNull { it.name == name }?.tap }
					// A widget that HA removes closes its screen, which would otherwise reopen if it came back.
					LaunchedEffect(openTap) { if (openTap == null) widgetOpen = null }
					val cameraConnection = connection
					val screen =
						when {
							activeCall != null -> Screen.Call(activeCall)
							activePhoneCall != null -> Screen.Phone(activePhoneCall)
							camerasOpen && cameraConnection != null && cameras.isNotEmpty() -> Screen.Cameras(cameraConnection)
							contactsOpen && contacts.isNotEmpty() -> Screen.Contacts
							scanOpen -> Screen.Scan
							settingsOpen || needsSetup -> Screen.Settings
							openTap != null -> Screen.WidgetDetail(openTap)
							menuOpen -> Screen.Menu
							screensaver -> Screen.Photos
							else -> Screen.Main
						}
					// The screen does not turn off during a call, nor over the photos if HA asks for it.
					LocalView.current.keepScreenOn = call != null || phoneCall != null || camerasOpen || scanOpen ||
						(screen == Screen.Photos && photosConfig?.keepScreenOn == true)
					when (screen) {
						is Screen.Call -> {
							CallScreen(
								name = screen.call.name,
								answered = screen.call.answered,
								onAnswer = runtime.phone::answer,
								onHangUp = runtime.phone::hangUp,
							) {
								IntercomVideo(
									camera = ringingCamera(screen.call, cameras),
									connection = connection,
									onVideoView = runtime.phone::setVideoView,
								)
							}
						}

						is Screen.Phone -> {
							CallScreen(
								name = screen.call.name,
								answered = screen.call.answered,
								onAnswer = runtime.line::answer,
								onHangUp = runtime.line::hangUp,
							)
						}

						is Screen.Cameras -> {
							BackHandler { camerasOpen = false }
							CameraScreen(
								cameras = cameras,
								connection = screen.connection,
								onTalk = runtime.phone::callIntercom,
								onClose = { camerasOpen = false },
							)
						}

						Screen.Contacts -> {
							BackHandler { contactsOpen = false }
							ContactsScreen(
								contacts = contacts,
								onCall = { contact ->
									contactsOpen = false
									runtime.line.dial(contact.number)
								},
								onClose = { contactsOpen = false },
							)
						}

						Screen.Scan -> {
							BackHandler { scanOpen = false }
							QrScanScreen(
								onInvite = { invite ->
									scanOpen = false
									runtime.enroll(invite)
								},
								onClose = { scanOpen = false },
							)
						}

						Screen.Settings -> {
							BackHandler(enabled = settingsOpen) { settingsOpen = false }
							SettingsScreen(
								initial = connection,
								status = PanelWarnings.haStatus(haStatus),
								intercoms = intercoms,
								enrollmentMessage = enrollmentMessage,
								onScan = { scanOpen = true },
								onEnroll = runtime::enroll,
								onOpenAndroidSettings = {
									kiosk.pause()
									startActivity(Intent(Settings.ACTION_SETTINGS))
								},
								onLeaveKiosk =
									if (kioskActive) {
										{
											kiosk.remove()
											kioskActive = false
										}
									} else {
										null
									},
								onClose = if (needsSetup) null else ({ settingsOpen = false }),
							)
						}

						is Screen.WidgetDetail -> {
							BackHandler { widgetOpen = null }
							WidgetScreen(tap = screen.tap, onClose = { widgetOpen = null })
						}

						Screen.Menu -> {
							BackHandler { menuOpen = false }
							PanelMenu(
								onCameras =
									if (cameras.isEmpty()) {
										null
									} else {
										{
											menuOpen = false
											camerasOpen = true
										}
									},
								onPhone =
									if (contacts.isEmpty()) {
										null
									} else {
										{
											menuOpen = false
											contactsOpen = true
										}
									},
								onSettings = {
									menuOpen = false
									settingsOpen = true
								},
								onClose = { menuOpen = false },
							)
						}

						Screen.Photos -> {
							PhotoScreen(
								photos = photos,
								haUrl = connection?.url.orEmpty(),
								config = photosConfig ?: PhotosConfig(),
								playlist = runtime.photoPlaylist,
								shapes = runtime.photoShapes,
							)
						}

						Screen.Main -> {
							ClockScreen(
								warning = warning,
								droid = droid,
								widgets = widgets,
								onOpenMenu = { menuOpen = true },
								onOpenWidget = { widgetOpen = it.name },
								onFixPermissions = if (missingPermissions.isEmpty()) null else ::askForPermissions,
							)
						}
					}
					// The droid says it on the main screen: nothing fixed there, as the OLED would keep it.
					if (cameraOn && screen != Screen.Main) RecordingMark()
				}
			}
		}
	}

	override fun onStart() {
		super.onStart()
		refreshPermissions()
		hideSystemBars()
		if (missingPermissions.isEmpty()) startPanel() else permissions.launch(missingPermissions.toTypedArray())
	}

	/** In `onStart` Android does not yet allow pinning the app: the activity does not have focus yet. */
	override fun onResume() {
		super.onResume()
		kiosk.enter()
		kioskActive = kiosk.isAvailable
	}

	private fun refreshPermissions() {
		missingPermissions =
			listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
				.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
	}

	private fun askForPermissions() {
		askedByTap = true
		permissions.launch(missingPermissions.toTypedArray())
	}

	private fun openAppSettings() {
		kiosk.pause()
		startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
	}

	/** The service is of type `microphone`: without the permission, Android does not let it start. */
	private fun startPanel() {
		if (micGranted) startForegroundService(Intent(this, PanelService::class.java))
	}

	private fun hideSystemBars() {
		window.insetsController?.apply {
			hide(WindowInsets.Type.systemBars())
			systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
		}
	}
}

/**
 * What fills the screen: the first that applies, in this order. The intercom comes before the phone line: the door does
 * not wait, and the phone call goes on behind it.
 */
private sealed interface Screen {
	data class Call(
		val call: IntercomCall,
	) : Screen

	data class Phone(
		val call: PhoneCall,
	) : Screen

	data class Cameras(
		val connection: HaConnection,
	) : Screen

	/** The phone contacts, to call one. */
	data object Contacts : Screen

	data object Scan : Screen

	data object Settings : Screen

	data class WidgetDetail(
		val tap: WidgetTap,
	) : Screen

	data object Menu : Screen

	/** The screensaver, in place of the main screen when nobody touches it. */
	data object Photos : Screen

	data object Main : Screen
}

/** The camera is on, on the screens where the droid is not. */
@Composable
private fun RecordingMark() {
	Box(Modifier.fillMaxSize()) {
		Box(
			Modifier
				.align(Alignment.TopCenter)
				.padding(top = 8.dp)
				.size(16.dp)
				.background(PixelRed),
		)
	}
}
