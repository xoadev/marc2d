package dev.xoa.marc2d

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import kotlin.math.abs
import kotlin.math.max
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

/** Lee los sensores del móvil que el panel publica en Home Assistant. */
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
			// Android la da en décimas de grado.
			temperature = (intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / TENTHS).roundToInt(),
			charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
		)
	}

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
		private const val TENTHS = 10f
		private const val MIN_LUX_CHANGE = 2f
		private const val MIN_RELATIVE_CHANGE = 0.2f

		/** Un cambio pequeño no se publica: el sensor oscila mucho y llenaría el historial de HA. */
		fun isSignificant(
			last: Float?,
			lux: Float,
		): Boolean = last == null || abs(lux - last) >= max(MIN_LUX_CHANGE, last * MIN_RELATIVE_CHANGE)
	}
}
