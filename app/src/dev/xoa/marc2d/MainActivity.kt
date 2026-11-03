package dev.xoa.marc2d

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
	private var micGranted by mutableStateOf(false)
	private var kioskActive by mutableStateOf(false)
	private val kiosk by lazy { Kiosk(this) }

	private val permissions =
		registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
			refreshPermissions()
			startPanel()
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		// In code because `screenOrientation` in the manifest had no effect on the 3a.
		requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
		val runtime = (application as Marc2dApp).runtime

		setContent {
			MaterialTheme(colorScheme = darkColorScheme()) {
				Surface {
					val connection by runtime.store.connection.collectAsState(initial = null)
					val haStatus by runtime.haStatus.collectAsState()
					val mqttStatus by runtime.mqtt.status.collectAsState()
					val intercoms by runtime.phone.status.collectAsState()
					val call by runtime.phone.call.collectAsState()
					val weather by runtime.weather.collectAsState()
					val haMood by runtime.haMood.collectAsState()
					val dark by runtime.dark.collectAsState()
					val scope = rememberCoroutineScope()
					var settingsOpen by rememberSaveable { mutableStateOf(false) }
					var menuOpen by rememberSaveable { mutableStateOf(false) }
					var camerasOpen by rememberSaveable { mutableStateOf(false) }
					val cameras by runtime.store.config
						.map { it.cameras }
						.collectAsState(initial = emptyList())

					// The screen does not turn off during a call.
					LocalView.current.keepScreenOn = call != null || camerasOpen

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

						settingsOpen || needsSetup -> {
							BackHandler(enabled = settingsOpen) { settingsOpen = false }
							SettingsScreen(
								initial = connection,
								status = PanelWarnings.haStatus(haStatus),
								intercoms = intercoms,
								onSave = { scope.launch { runtime.store.saveConnection(it) } },
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
							val warning = PanelWarnings.warning(haStatus, intercoms, micGranted, mqttStatus)
							ClockScreen(
								warning = warning,
								droid = DroidMoods.resolve(haMood, hasProblem = warning != null, dark = dark),
								weather = weather,
								onOpenMenu = { menuOpen = true },
							)
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
		val missing =
			listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
				.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
		if (missing.isEmpty()) startPanel() else permissions.launch(missing.toTypedArray())
	}

	/** In `onStart` Android does not yet allow pinning the app: the activity does not have focus yet. */
	override fun onResume() {
		super.onResume()
		kiosk.enter()
		kioskActive = kiosk.isAvailable
	}

	private fun refreshPermissions() {
		micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
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
