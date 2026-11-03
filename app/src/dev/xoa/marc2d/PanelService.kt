package dev.xoa.marc2d

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Mantiene el registro SIP y la conexión con Home Assistant, porque el telefonillo no manda push.
 * Es de tipo `microphone` y lo arranca la pantalla de inicio: Android 15 no deja lanzar este tipo
 * desde `BOOT_COMPLETED`.
 */
class PanelService : Service() {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		val manager = getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(
			NotificationChannel(CHANNEL_RUNNING, "Panel en marcha", NotificationManager.IMPORTANCE_MIN),
		)
		manager.createNotificationChannel(
			NotificationChannel(CHANNEL_CALL, "Llamadas del telefonillo", NotificationManager.IMPORTANCE_HIGH).apply {
				// El tono lo pone Linphone.
				setSound(null, null)
			},
		)

		try {
			startForeground(NOTIFICATION_RUNNING, runningNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
		} catch (_: ForegroundServiceStartNotAllowedException) {
			// La pantalla de inicio lo volverá a arrancar cuando esté visible.
			stopSelf()
			return
		}

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
	): Int = START_NOT_STICKY

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

	/** Si el panel no está delante, la intención a pantalla completa lo trae. */
	private fun callNotification(call: IntercomCall): Notification =
		Notification
			.Builder(this, CHANNEL_CALL)
			.setSmallIcon(R.drawable.ic_launcher)
			.setContentTitle("Llaman desde ${call.name}")
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
