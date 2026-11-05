package dev.xoa.marc2d

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonPrimitive

/** A voice request: the audio after the wake phrase, 16 kHz mono PCM, ended by closing it. */
class AssistRun(
	val pipeline: String?,
	val audio: ReceiveChannel<ByteArray>,
	val onStopListening: () -> Unit,
)

/** What goes up to Home Assistant. It holds the readings, to send them all again on each connection. */
class PanelLink {
	private val _values = MutableStateFlow(emptyMap<String, JsonPrimitive>())
	val values: StateFlow<Map<String, JsonPrimitive>> = _values.asStateFlow()

	private val _answered = MutableSharedFlow<String>(extraBufferCapacity = 8)
	val answered: SharedFlow<String> = _answered

	fun report(
		key: String,
		value: JsonPrimitive,
	) {
		_values.update { it + (key to value) }
	}

	fun forget(key: String) {
		_values.update { it - key }
	}

	private val _assist = MutableSharedFlow<AssistRun>(extraBufferCapacity = 1)
	val assist: MutableSharedFlow<AssistRun> get() = _assist

	fun requestAssist(run: AssistRun) {
		_assist.tryEmit(run)
	}

	/** Tells the other panels to stop ringing. */
	fun answer(intercom: String) {
		_answered.tryEmit(intercom)
	}

	companion object {
		const val SIREN = "siren"
		const val PLAYING = "playing"
		const val VOLUME = "volume"
		const val ADDRESS = "address"
		val SENSORS = listOf("light", "battery", "temperature", "charging", "presence")
	}
}
