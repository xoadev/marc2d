package dev.xoa.marc2d

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.linphone.core.Account
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.MediaDirection
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class IntercomStatus { Registering, Registered, Failed }

data class IntercomCall(
	val name: String,
	val answered: Boolean,
)

/**
 * Registers the panel on the intercoms and handles their calls with Linphone. Everything is called from the
 * main thread, on which Linphone iterates and delivers its events.
 */
class IntercomPhone(
	private val context: Context,
) {
	private val _status = MutableStateFlow<Map<String, IntercomStatus>>(emptyMap())
	val status: StateFlow<Map<String, IntercomStatus>> = _status.asStateFlow()

	private val _call = MutableStateFlow<IntercomCall?>(null)
	val call: StateFlow<IntercomCall?> = _call.asStateFlow()

	private var applied: PanelConfig? = null
	private var activeCall: Call? = null

	/** The intercom whose doorbell is ringing, notified over MQTT: there is no SIP call yet. */
	private var ringing: IntercomConfig? = null
	private var ringer: Ringtone? = null
	private val main = Handler(Looper.getMainLooper())
	private val ringTimeout = Runnable { stopDoorbell() }

	/** Who is being called after answering, until the VTO picks up. */
	private var dialing: IntercomConfig? = null
	private var dialDeadline = 0L
	private val redial = Runnable { dialing?.let(::placeCall) }

	var onDoorbellAnswered: (String) -> Unit = {}

	private val core: Core =
		Factory.instance().createCore(null, null, context).apply {
			// The panel has no Google services, and the intercoms do not send push.
			isPushNotificationEnabled = false
			isVideoCaptureEnabled = false
			isVideoDisplayEnabled = true
			videoActivationPolicy =
				Factory.instance().createVideoActivationPolicy().apply {
					automaticallyAccept = true
					automaticallyInitiate = false
				}
			addListener(listener())
			start()
		}

	fun apply(config: PanelConfig) {
		// Other configuration changes, such as the weather, must not interrupt registration or a call.
		if (config.intercoms == applied?.intercoms) {
			applied = config
			return
		}
		applied = config

		core.clearAccounts()
		core.clearAllAuthInfo()
		_status.value = config.intercoms.associate { it.name to IntercomStatus.Registering }

		config.intercoms.forEach { intercom ->
			val params = core.createAccountParams()
			params.identityAddress = Factory.instance().createAddress(intercom.sipIdentity)
			params.serverAddress =
				Factory.instance().createAddress("sip:${intercom.server}")?.apply {
					transport = TransportType.Udp
				}
			params.isRegisterEnabled = true
			core.addAuthInfo(
				Factory.instance().createAuthInfo(
					intercom.extension,
					null,
					intercom.password,
					null,
					null,
					intercom.server,
				),
			)
			val account = core.createAccount(params)
			core.addAccount(account)
			if (core.defaultAccount == null) core.defaultAccount = account
		}
	}

	fun doorbell(
		intercom: String,
		payload: String,
	) {
		when (payload) {
			PanelTopics.RING -> ring(intercom)
			PanelTopics.ANSWERED -> if (ringing?.let { PanelTopics.slug(it.name) } == intercom) stopDoorbell()
		}
	}

	private fun ring(slug: String) {
		if (activeCall != null || ringing != null) return
		val intercom = applied?.intercoms?.firstOrNull { PanelTopics.slug(it.name) == slug } ?: return
		ringing = intercom
		_call.value = IntercomCall(intercom.name, answered = false)
		ringer =
			RingtoneManager
				.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
				?.apply {
					isLooping = true
					play()
				}
		main.postDelayed(ringTimeout, RING_TIMEOUT_MILLIS)
	}

	private fun silence() {
		main.removeCallbacks(ringTimeout)
		ringer?.stop()
		ringer = null
		ringing = null
	}

	private fun stopDoorbell() {
		silence()
		if (activeCall == null) _call.value = null
	}

	/** The VTO picks up by itself and provides audio and video: the panel starts the call on answering. */
	private fun dial(intercom: IntercomConfig) {
		silence()
		onDoorbellAnswered(PanelTopics.slug(intercom.name))
		_call.value = IntercomCall(intercom.name, answered = true)
		dialing = intercom
		dialDeadline = SystemClock.elapsedRealtime() + DIAL_TIMEOUT_MILLIS
		placeCall(intercom)
	}

	private fun placeCall(intercom: IntercomConfig) {
		val address = Factory.instance().createAddress("sip:${intercom.number}@${intercom.server}")
		val params = core.createCallParams(null)
		if (address != null && params != null) {
			params.isVideoEnabled = true
			params.videoDirection = MediaDirection.RecvOnly
			activeCall = core.inviteAddressWithParams(address, params)
		}
		if (activeCall == null) retryOrEnd()
	}

	/** After a button press, the VTO does not answer while its own group call lasts: keep trying. */
	private fun retryOrEnd() {
		if (dialing != null && SystemClock.elapsedRealtime() < dialDeadline) {
			main.postDelayed(redial, REDIAL_DELAY_MILLIS)
		} else {
			dialing = null
			_call.value = null
		}
	}

	/** To talk through the intercom without anyone having rung. */
	fun callIntercom(name: String) {
		if (activeCall != null || dialing != null) return
		applied?.intercoms?.firstOrNull { it.name == name }?.let(::dial)
	}

	fun answer() {
		ringing?.let {
			dial(it)
			return
		}
		val call = activeCall ?: return
		val params = core.createCallParams(call) ?: return
		params.isVideoEnabled = true
		// The door station is visible, but the panel sends no image.
		params.videoDirection = MediaDirection.RecvOnly
		call.acceptWithParams(params)
	}

	fun hangUp() {
		if (ringing != null) {
			stopDoorbell()
			return
		}
		dialing = null
		main.removeCallbacks(redial)
		val call = activeCall
		if (call == null) {
			_call.value = null
			return
		}
		if (call.state == Call.State.IncomingReceived || call.state == Call.State.IncomingEarlyMedia) {
			call.decline(Reason.Declined)
		} else {
			call.terminate()
		}
	}

	fun setVideoView(view: Any?) {
		core.nativeVideoWindowId = view
	}

	private fun listener() =
		object : CoreListenerStub() {
			override fun onAccountRegistrationStateChanged(
				core: Core,
				account: Account,
				state: RegistrationState?,
				message: String,
			) {
				val name = intercomName(account) ?: return
				val status =
					when (state) {
						RegistrationState.Ok -> IntercomStatus.Registered
						RegistrationState.Failed -> IntercomStatus.Failed
						else -> IntercomStatus.Registering
					}
				_status.update { it + (name to status) }
			}

			override fun onCallStateChanged(
				core: Core,
				call: Call,
				state: Call.State?,
				message: String,
			) {
				when (state) {
					Call.State.IncomingReceived, Call.State.IncomingEarlyMedia -> {
						silence()
						activeCall = call
						_call.value = IntercomCall(callName(call), answered = false)
					}

					Call.State.Connected, Call.State.StreamsRunning -> {
						activeCall = call
						dialing = null
						routeToSpeaker(call)
						_call.value = IntercomCall(callName(call), answered = true)
					}

					Call.State.End, Call.State.Error, Call.State.Released -> {
						if (activeCall == call) {
							activeCall = null
							retryOrEnd()
						}
					}

					else -> {}
				}
			}
		}

	private companion object {
		// The same time the VTO lets it ring.
		const val RING_TIMEOUT_MILLIS = 45_000L
		const val DIAL_TIMEOUT_MILLIS = 45_000L
		const val REDIAL_DELAY_MILLIS = 2_000L
	}

	/** There is no earpiece on the wall: always use the speaker. */
	private fun routeToSpeaker(call: Call) {
		core.audioDevices
			.firstOrNull { it.type == AudioDevice.Type.Speaker }
			?.let { call.outputAudioDevice = it }
	}

	private fun intercomName(account: Account): String? {
		val server = account.params.identityAddress?.domain
		return applied?.intercoms?.firstOrNull { it.server == server }?.name
	}

	private fun callName(call: Call): String {
		val server = call.remoteAddress.domain
		return applied?.intercoms?.firstOrNull { it.server == server }?.name
			?: call.remoteAddress.displayName
			?: "Intercom"
	}
}
