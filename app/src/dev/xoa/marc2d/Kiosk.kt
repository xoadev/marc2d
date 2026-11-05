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
 * Kiosk mode. It only activates if the app is the device owner, which is set up over
 * `adb` once: `dpm set-device-owner dev.xoa.marc2d/.PanelAdminReceiver`.
 */
class Kiosk(
	private val activity: Activity,
) {
	private val policies = activity.getSystemService(DevicePolicyManager::class.java)
	private val admin = ComponentName(activity, PanelAdminReceiver::class.java)

	val isAvailable: Boolean
		get() = policies.isDeviceOwnerApp(activity.packageName)

	/** Applies the policy and pins the app. Called every time the panel returns to the foreground. */
	fun enter() {
		if (!isAvailable) return

		policies.setLockTaskPackages(admin, arrayOf(activity.packageName))
		policies.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
		// A wall panel has to come back by itself after a power cut, without anyone unlocking it.
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
		listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA).forEach {
			policies.setPermissionGrantState(admin, activity.packageName, it, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
		}

		val manager = activity.getSystemService(ActivityManager::class.java)
		if (manager.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) activity.startLockTask()
	}

	/** Releases the app until the panel returns to the foreground, to open the Android settings. */
	fun pause() {
		if (isAvailable) activity.stopLockTask()
	}

	/** Undoes everything: the app stops being the device owner and can be uninstalled. */
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
