package dev.xoa.marc2d

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the SIP registration and the connection to Home Assistant, because the intercom does not send push.
 * It is of type `microphone` and `camera`, and the home screen starts it: Android 15 does not allow launching these
 * types from `BOOT_COMPLETED`, and without them it could not use either while the screen is off.
 */
class PanelService : Service() {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		val manager = getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(
			NotificationChannel(CHANNEL_RUNNING, "Panel running", NotificationManager.IMPORTANCE_MIN),
		)
		manager.createNotificationChannel(
			NotificationChannel(CHANNEL_CALL, "Intercom calls", NotificationManager.IMPORTANCE_HIGH).apply {
				// Linphone provides the ringtone.
				setSound(null, null)
			},
		)

		if (!promote()) return

		val runtime = (application as Marc2dApp).runtime
		runtime.start()
		scope.launch {
			runtime.phone.call.collect { call ->
				if (call == null || call.answered) {
					manager.cancel(NOTIFICATION_CALL)
				} else {
					manager.notify(NOTIFICATION_CALL, callNotification(call))
				}
			}
		}
	}

	override fun onStartCommand(
		intent: Intent?,
		flags: Int,
		startId: Int,
	): Int {
		// The camera permission may have arrived later, when scanning the QR code: the home screen is visible now.
		promote()
		return START_NOT_STICKY
	}

	private fun promote(): Boolean {
		val camera = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
		val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or (if (camera) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
		return try {
			startForeground(NOTIFICATION_RUNNING, runningNotification(), types)
			true
		} catch (_: ForegroundServiceStartNotAllowedException) {
			// The home screen will start it again when it is visible.
			stopSelf()
			false
		}
	}

	override fun onDestroy() {
		scope.cancel()
		super.onDestroy()
	}

	private fun openPanel(): PendingIntent =
		PendingIntent.getActivity(
			this,
			0,
			Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)

	private fun runningNotification(): Notification =
		Notification
			.Builder(this, CHANNEL_RUNNING)
			.setSmallIcon(R.drawable.ic_launcher)
			.setContentTitle("marc2d")
			.setContentIntent(openPanel())
			.setOngoing(true)
			.build()

	/** If the panel is not in front, the full-screen intent brings it. */
	private fun callNotification(call: IntercomCall): Notification =
		Notification
			.Builder(this, CHANNEL_CALL)
			.setSmallIcon(R.drawable.ic_launcher)
			.setContentTitle("Call from ${call.name}")
			.setCategory(Notification.CATEGORY_CALL)
			.setFullScreenIntent(openPanel(), true)
			.setOngoing(true)
			.build()

	private companion object {
		const val CHANNEL_RUNNING = "running"
		const val CHANNEL_CALL = "call"
		const val NOTIFICATION_RUNNING = 1
		const val NOTIFICATION_CALL = 2
	}
}
