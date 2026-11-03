package dev.xoa.marc2d

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter

class PanelAdminReceiver : DeviceAdminReceiver()

/**
 * Modo quiosco. Solo se activa si la app es propietaria del dispositivo, algo que se configura por
 * `adb` una vez: `dpm set-device-owner dev.xoa.marc2d/.PanelAdminReceiver`.
 */
class Kiosk(
	private val activity: Activity,
) {
	private val policies = activity.getSystemService(DevicePolicyManager::class.java)
	private val admin = ComponentName(activity, PanelAdminReceiver::class.java)

	val isAvailable: Boolean
		get() = policies.isDeviceOwnerApp(activity.packageName)

	/** Aplica la política y fija la app. Se llama cada vez que el panel vuelve a primer plano. */
	fun enter() {
		if (!isAvailable) return

		policies.setLockTaskPackages(admin, arrayOf(activity.packageName))
		policies.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
		// Un panel de pared tiene que volver solo tras un corte de luz, sin que nadie lo desbloquee.
		policies.setKeyguardDisabled(admin, true)
		policies.setStatusBarDisabled(admin, true)
		policies.addPersistentPreferredActivity(
			admin,
			IntentFilter(Intent.ACTION_MAIN).apply {
				addCategory(Intent.CATEGORY_HOME)
				addCategory(Intent.CATEGORY_DEFAULT)
			},
			ComponentName(activity, MainActivity::class.java),
		)
		listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS).forEach {
			policies.setPermissionGrantState(admin, activity.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
		}

		val manager = activity.getSystemService(ActivityManager::class.java)
		if (manager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) activity.startLockTask()
	}

	/** Suelta la app hasta que el panel vuelva a primer plano, para abrir los ajustes de Android. */
	fun pause() {
		if (isAvailable) activity.stopLockTask()
	}

	/** Deshace todo: la app deja de ser propietaria del dispositivo y se puede desinstalar. */
	fun remove() {
		if (!isAvailable) return

		activity.stopLockTask()
		policies.setStatusBarDisabled(admin, false)
		policies.setKeyguardDisabled(admin, false)
		policies.setLockTaskPackages(admin, emptyArray())
		policies.clearPackagePersistentPreferredActivities(admin, activity.packageName)
		policies.clearDeviceOwnerApp(activity.packageName)
	}
}
