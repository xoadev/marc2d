package dev.xoa.marc2d

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A call on the SIM's line, named by its caller: the number, or what the network says of it. */
data class PhoneCall(
	val name: String,
	val answered: Boolean,
)

/** What the call screen shows for a Telecom [state]: nothing once the call is going away. */
internal fun phoneCall(
	state: Int,
	name: String,
): PhoneCall? =
	when (state) {
		Call.STATE_DISCONNECTING, Call.STATE_DISCONNECTED -> null
		Call.STATE_NEW, Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> PhoneCall(name, answered = false)
		else -> PhoneCall(name, answered = true)
	}

/**
 * The phone line's call, which Telecom hands to [PanelInCallService] because the panel is the default dialer.
 * Everything runs on the main thread, where Telecom delivers its callbacks.
 */
class PhoneLine {
	private val _call = MutableStateFlow<PhoneCall?>(null)
	val call: StateFlow<PhoneCall?> = _call.asStateFlow()

	private var current: Call? = null

	/** ponytail: one call at a time, a second one is turned down; call waiting would need a list on screen. */
	internal fun take(call: Call): Boolean {
		if (current != null) return false
		current = call
		return true
	}

	internal fun follow(call: Call) {
		if (call != current) return
		val details = call.details
		val number = details.handle?.schemeSpecificPart
		publish(phoneCall(details.state, details.callerDisplayName?.ifBlank { null } ?: number ?: UNKNOWN_CALLER))
	}

	internal fun release(call: Call) {
		if (call != current) return
		current = null
		publish(null)
	}

	fun answer() {
		current?.answer(VideoProfile.STATE_AUDIO_ONLY)
	}

	/** Also turns down a call that is still ringing. */
	fun hangUp() {
		current?.disconnect()
	}

	/** The number never reaches the log: only what happened. */
	private fun publish(next: PhoneCall?) {
		val phase = phase(next)
		if (phase != phase(_call.value)) Log.i(TAG, "Phone call $phase")
		_call.value = next
	}

	private fun phase(call: PhoneCall?): String =
		when {
			call == null -> "ended"
			call.answered -> "answered"
			else -> "ringing"
		}

	private companion object {
		const val TAG = "marc2d"
		const val UNKNOWN_CALLER = "Unknown number"
	}
}

/**
 * Telecom's link to the panel's call screen. Telecom keeps the ringtone, which it plays unless the service says it
 * rings by itself.
 */
class PanelInCallService : InCallService() {
	private val line get() = (application as Marc2dApp).runtime.line

	private val callback =
		object : Call.Callback() {
			override fun onStateChanged(
				call: Call,
				state: Int,
			) = follow(call)
		}

	override fun onCallAdded(call: Call) {
		if (!line.take(call)) {
			call.disconnect()
			return
		}
		call.registerCallback(callback)
		follow(call)
	}

	override fun onCallRemoved(call: Call) {
		call.unregisterCallback(callback)
		line.release(call)
	}

	private fun follow(call: Call) {
		line.follow(call)
		// On the wall nobody holds it to the ear.
		@Suppress("DEPRECATION") // Its replacement needs the endpoint list first; this still routes on Android 15.
		if (call.details.state == Call.STATE_ACTIVE) setAudioRoute(CallAudioState.ROUTE_SPEAKER)
	}
}
