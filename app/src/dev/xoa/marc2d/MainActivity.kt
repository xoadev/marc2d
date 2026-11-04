package dev.xoa.marc2d

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {
	private var micGranted by mutableStateOf(false)
	private var kioskActive by mutableStateOf(false)
	private val kiosk by lazy { Kiosk(this) }

	private var missingPermissions by mutableStateOf(emptyList<String>())
	private var askedByTap = false

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
					val weather by runtime.weather.collectAsState()
					val haMood by runtime.haMood.collectAsState()
					val dark by runtime.dark.collectAsState()
					val listening by runtime.listening.collectAsState()
					val brightness by runtime.brightness.collectAsState()
					LaunchedEffect(brightness) {
						brightness?.let { level ->
							window.attributes = window.attributes.apply { screenBrightness = level }
						}
					}
					var settingsOpen by rememberSaveable { mutableStateOf(false) }
					var menuOpen by rememberSaveable { mutableStateOf(false) }
					var camerasOpen by rememberSaveable { mutableStateOf(false) }
					var scanOpen by rememberSaveable { mutableStateOf(false) }
					val enrollmentMessage by runtime.enrollmentMessage.collectAsState()
					// Remembered: a new flow on each recomposition would restart the collection every time.
					val cameras by remember { runtime.store.config.map { it.cameras } }.collectAsState(initial = emptyList())

					// The screen does not turn off during a call.
					LocalView.current.keepScreenOn = call != null || camerasOpen || scanOpen

					val activeCall = call
					val needsSetup = haStatus == HaStatus.NotConfigured
					when {
						activeCall != null -> {
							CallScreen(
								call = activeCall,
								onVideoView = runtime.phone::setVideoView,
								onAnswer = runtime.phone::answer,
								onHangUp = runtime.phone::hangUp,
							)
						}

						camerasOpen && connection != null && cameras.isNotEmpty() -> {
							BackHandler { camerasOpen = false }
							CameraScreen(
								cameras = cameras,
								connection = connection!!,
								onTalk = runtime.phone::callIntercom,
								onClose = { camerasOpen = false },
							)
						}

						scanOpen -> {
							BackHandler { scanOpen = false }
							QrScanScreen(
								onInvite = { invite ->
									scanOpen = false
									runtime.enroll(invite)
								},
								onClose = { scanOpen = false },
							)
						}

						settingsOpen || needsSetup -> {
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

						menuOpen -> {
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
								onSettings = {
									menuOpen = false
									settingsOpen = true
								},
								onClose = { menuOpen = false },
							)
						}

						else -> {
							val warning = PanelWarnings.warning(haStatus, intercoms, micGranted)
							ClockScreen(
								warning = warning,
								droid = DroidMoods.resolve(haMood, hasProblem = warning != null, dark = dark, listening = listening),
								weather = weather,
								onOpenMenu = { menuOpen = true },
								onFixPermissions = if (missingPermissions.isEmpty()) null else ::askForPermissions,
							)
						}
					}
					// Over any screen: it must be clear when the microphone reaches Home Assistant.
					if (listening && activeCall == null) {
						Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
							PixelGlyph(PixelIcon.Microphone, PixelWhite, Modifier.size(96.dp).semantics { contentDescription = "Listening" })
						}
					}
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
		micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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
