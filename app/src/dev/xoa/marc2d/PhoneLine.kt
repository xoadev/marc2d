package dev.xoa.marc2d

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.TelephonyManager
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

/** From this many digits, a number matches a longer one that ends with it: 600123456 is +34 600 12 34 56. */
private const val MIN_MATCHING_DIGITS = 7

/** The contact with this number, compared by its digits, whatever the spaces, dashes or prefix of either. */
internal fun contactFor(
	contacts: List<Contact>,
	number: String?,
): Contact? {
	val digits = number?.filter(Char::isDigit).orEmpty()
	if (digits.isEmpty()) return null
	return contacts.firstOrNull { sameNumber(it.number.filter(Char::isDigit), digits) }
}

// ponytail: two numbers that differ only in their country code look the same, as with Android's old PhoneNumberUtils.compare;
// libphonenumber would tell them apart, for the weight of its metadata.
private fun sameNumber(
	a: String,
	b: String,
): Boolean {
	val (shorter, longer) = if (a.length <= b.length) a to b else b to a
	return shorter == longer || (shorter.length >= MIN_MATCHING_DIGITS && longer.endsWith(shorter))
}

/**
 * The phone line's call, which Telecom hands to [PanelInCallService] because the panel is the default dialer.
 * Everything runs on the main thread, where Telecom delivers its callbacks.
 */
class PhoneLine(
	private val context: Context,
) {
	/** From HA: they name the calls and the panel calls them. */
	@Volatile var contacts: List<Contact> = emptyList()

	/** Whether there is a SIM ready to call: without it the panel offers no phone. A panel need not have one. */
	private val _sim = MutableStateFlow(false)
	val sim: StateFlow<Boolean> = _sim.asStateFlow()

	/** A SIM can be put in or taken out while the panel runs: it is looked at again from time to time. */
	fun refreshSim(): Boolean {
		val ready = context.getSystemService(TelephonyManager::class.java)?.simState == TelephonyManager.SIM_STATE_READY
		if (ready != _sim.value) Log.i(TAG, if (ready) "SIM ready: the phone is on" else "No SIM ready: the phone is off")
		_sim.value = ready
		return ready
	}

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
		val name = contactFor(contacts, number)?.name ?: details.callerDisplayName?.ifBlank { null } ?: number ?: UNKNOWN_CALLER
		// Why it ended says, for instance, that there is no SIM or no signal. Never the number.
		if (details.state == Call.STATE_DISCONNECTED) Log.i(TAG, "Phone call disconnected: ${details.disconnectCause}")
		publish(phoneCall(details.state, name))
	}

	internal fun release(call: Call) {
		if (call != current) return
		current = null
		publish(null)
	}

	fun answer() {
		current?.answer(VideoProfile.STATE_AUDIO_ONLY)
	}

	/**
	 * Calls over the SIM, on the speaker. Telecom hands the call back to [PanelInCallService], as the panel is the dialer, and
	 * it shows on the call screen like any other.
	 */
	fun dial(number: String) {
		if (!refreshSim()) {
			Log.w(TAG, "Phone call not made: there is no SIM ready")
			return
		}
		if (current != null) {
			Log.w(TAG, "Phone call not made: there is one already")
			return
		}
		if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
			Log.w(TAG, "Phone call not made: the app may not call. The kiosk grants it: open the app again, or grant Phone in its settings")
			return
		}
		val extras = Bundle().apply { putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true) }
		try {
			context.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), extras)
			Log.i(TAG, "Phone call dialed")
		} catch (e: SecurityException) {
			// Not its message: it may carry the number.
			Log.w(TAG, "Phone call not made: Telecom refused it (${e::class.simpleName})")
		}
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
