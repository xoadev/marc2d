package dev.xoa.marc2d

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

data class BatteryReading(
	val level: Int,
	val temperature: Int,
	val charging: Boolean,
)

/** Which light readings go to Home Assistant. It forgets the last one while the sensors are off: they restart with a value. */
class LightReports {
	private var last: Float? = null

	fun shouldReport(
		lux: Float,
		enabled: Boolean,
	): Boolean {
		if (!enabled) {
			last = null
			return false
		}
		if (!PanelReadings.isSignificant(last, lux)) return false
		last = lux
		return true
	}
}

/** Which Wi-Fi readings go to Home Assistant: it moves a few dB all the time, which would fill its history. */
class SignalReports {
	private var last: Int? = null

	fun shouldReport(
		dbm: Int,
		enabled: Boolean,
	): Boolean {
		if (!enabled) {
			last = null
			return false
		}
		val previous = last
		if (previous != null && abs(dbm - previous) < MIN_CHANGE_DB) return false
		last = dbm
		return true
	}

	private companion object {
		const val MIN_CHANGE_DB = 5
	}
}

/**
 * Guesses that someone is there from sudden changes of light: a shadow, or a lamp turned on. Slow changes, such as the
 * sun moving, are followed without noticing them. It cannot see anything in the dark, or someone who stays still.
 */
class LightPresence {
	private var baseline: Float? = null
	private var lastReading = 0L
	private var presentUntil = 0L
	private var ignoredUntil = 0L

	/** The panel's own screen also lights its sensor: without this, an automation that follows presence would feed itself. */
	fun ignoreChanges(now: Long) {
		ignoredUntil = now + IGNORE_MS
	}

	fun update(
		lux: Float,
		now: Long,
	) {
		val last = baseline
		when {
			last == null || now < ignoredUntil -> {
				baseline = lux
			}

			PanelReadings.isSignificant(last, lux) -> {
				presentUntil = now + HOLD_MS
				baseline = lux
			}

			else -> {
				baseline = last + (lux - last) * min(1f, (now - lastReading) / FOLLOW_MS)
			}
		}
		lastReading = now
	}

	/** How long it will still say that someone is there, in milliseconds. */
	fun remaining(now: Long): Long = max(0, presentUntil - now)

	private companion object {
		const val HOLD_MS = 60_000L
		const val IGNORE_MS = 5_000L
		const val FOLLOW_MS = 60_000f
	}
}

/** Reads the phone's sensors that the panel publishes to Home Assistant. */
class PanelReadings(
	private val context: Context,
) {
	fun battery(): BatteryReading? {
		val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
		val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
		val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
		if (level < 0 || scale <= 0) return null
		val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
		return BatteryReading(
			level = level * PERCENT / scale,
			// Android gives it in tenths of a degree.
			temperature = (intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / TENTHS).roundToInt(),
			charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
		)
	}

	/** In dBm, or nothing without Wi-Fi: a panel on Ethernet has none. The replacement needs a network callback for one number. */
	@Suppress("DEPRECATION")
	fun wifiSignal(): Int? =
		context
			.getSystemService(WifiManager::class.java)
			?.connectionInfo
			?.rssi
			?.takeIf { it in MIN_WIFI_DBM..MAX_WIFI_DBM }

	fun appVersion(): String? = context.packageManager.getPackageInfo(context.packageName, 0).versionName

	fun light(): Flow<Float> {
		val manager = context.getSystemService(SensorManager::class.java)
		val sensor = manager.getDefaultSensor(Sensor.TYPE_LIGHT) ?: return emptyFlow()
		return callbackFlow {
			val listener =
				object : SensorEventListener {
					override fun onSensorChanged(event: SensorEvent) {
						trySend(event.values[0])
					}

					override fun onAccuracyChanged(
						sensor: Sensor?,
						accuracy: Int,
					) = Unit
				}
			manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
			awaitClose { manager.unregisterListener(listener) }
		}
	}

	companion object {
		private const val PERCENT = 100
		private const val MIN_WIFI_DBM = -126
		private const val MAX_WIFI_DBM = -1
		private const val TENTHS = 10f
		private const val MIN_LUX_CHANGE = 2f
		private const val MIN_RELATIVE_CHANGE = 0.2f

		/** A small change is not published: the sensor fluctuates a lot and would fill HA's history. */
		fun isSignificant(
			last: Float?,
			lux: Float,
		): Boolean = last == null || abs(lux - last) >= max(MIN_LUX_CHANGE, last * MIN_RELATIVE_CHANGE)
	}
}
