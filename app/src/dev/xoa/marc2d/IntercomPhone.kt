package dev.xoa.marc2d

import android.content.Context
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
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
	/** Its video comes over RTSP, from the moment it rings, rather than in the SIP call. */
	val live: Boolean = false,
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

	/** The intercom's video, sound and the panel's voice, for one with an RTSP URL. */
	private var media: RtspCall? = null
	private var liveSurface: Surface? = null

	/** Whether the SIP call got through: once it ends, so does the conversation. */
	private var connected = false

	/** The intercom whose doorbell is ringing, notified by Home Assistant: there is no SIP call yet. */
	private var ringing: IntercomConfig? = null
	private val ringer =
		Ringer {
			RingtoneManager
				.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
				?.apply {
					isLooping = true
					play()
				}?.let { tone -> Ringer.Sound { tone.stop() } }
		}
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
			// The panel rings by itself, with the phone's ringtone, however the call arrives. Native ringing keeps Linphone's own
			// out of the core, and this keeps its Android helper from ringing again on top.
			isNativeRingingEnabled = true
			config.setInt("sound", "disable_ringing", 1)
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
			RING -> ring(intercom)
			ANSWERED -> if (ringing?.name == intercom) stopDoorbell()
		}
	}

	private fun ring(name: String) {
		if (activeCall != null || ringing != null) return
		val intercom = applied?.intercoms?.firstOrNull { it.name == name } ?: return
		ringing = intercom
		startMedia(intercom)
		show(intercom.name, answered = false)
		ringer.start()
		main.postDelayed(ringTimeout, RING_TIMEOUT_MILLIS)
	}

	private fun silence() {
		main.removeCallbacks(ringTimeout)
		ringer.stop()
		ringing = null
	}

	private fun stopDoorbell() {
		silence()
		if (activeCall == null && dialing == null) closeCall()
	}

	private fun show(
		name: String,
		answered: Boolean,
	) {
		_call.value = IntercomCall(name, answered, live = media != null)
	}

	/** The call screen goes, and with it the intercom's media. */
	private fun closeCall() {
		stopMedia()
		_call.value = null
	}

	/** Opens the intercom's media, when it has any, and the panel starts speaking. */
	private fun talk(intercom: IntercomConfig) {
		startMedia(intercom)
		media?.answer()
		show(intercom.name, answered = true)
	}

	private fun startMedia(intercom: IntercomConfig) {
		val url = intercom.rtsp ?: return
		if (media != null) return
		media =
			RtspCall(context, url).apply {
				setSurface(liveSurface)
				start()
			}
	}

	private fun stopMedia() {
		media?.stop()
		media = null
	}

	/** The VTO picks up by itself and provides audio and video: the panel starts the call on answering. */
	private fun dial(intercom: IntercomConfig) {
		silence()
		onDoorbellAnswered(intercom.name)
		talk(intercom)
		dialing = intercom
		dialDeadline = SystemClock.elapsedRealtime() + DIAL_TIMEOUT_MILLIS
		placeCall(intercom)
	}

	private fun placeCall(intercom: IntercomConfig) {
		val address = Factory.instance().createAddress("sip:${intercom.number}@${intercom.server}")
		val params = core.createCallParams(null)
		if (address != null && params != null) {
			callMedia(intercom, params)
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
			// Over RTSP the conversation goes on without SIP: only the intercom does not know someone answered.
			if (media?.isAnswered != true) closeCall()
		}
	}

	/**
	 * The call's media. Over RTSP, SIP only tells the intercom someone answered: neither sound nor video. Otherwise the door
	 * station is visible but sees nothing.
	 */
	private fun callMedia(
		intercom: IntercomConfig?,
		params: org.linphone.core.CallParams,
	) {
		if (intercom?.rtsp != null) {
			params.isVideoEnabled = false
			params.audioDirection = MediaDirection.Inactive
			return
		}
		params.isVideoEnabled = true
		params.videoDirection = MediaDirection.RecvOnly
	}

	/** To talk through the intercom without anyone having rung. */
	fun callIntercom(name: String) {
		if (activeCall != null || dialing != null || media != null) return
		val intercom = applied?.intercoms?.firstOrNull { it.name == name } ?: return
		if (intercom.rtsp == null) {
			dial(intercom)
			return
		}
		// Nobody rang: over RTSP there is no call to answer, it is enough to speak.
		talk(intercom)
	}

	fun answer() {
		ringing?.let {
			dial(it)
			return
		}
		val call = activeCall ?: return
		val params = core.createCallParams(call) ?: return
		callMedia(intercomFor(call), params)
		media?.answer()
		call.acceptWithParams(params)
	}

	fun hangUp() {
		if (ringing != null) {
			stopDoorbell()
			return
		}
		dialing = null
		main.removeCallbacks(redial)
		stopMedia()
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

	/** Where the RTSP video goes. */
	fun setLiveSurface(surface: Surface?) {
		liveSurface = surface
		media?.setSurface(surface)
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
						intercomFor(call)?.let(::startMedia)
						show(callName(call), answered = false)
						// A call can arrive without HA having said anything: it has to ring by itself too.
						ringer.start()
					}

					Call.State.Connected, Call.State.StreamsRunning -> {
						silence()
						activeCall = call
						dialing = null
						connected = true
						routeToSpeaker(call)
						show(callName(call), answered = true)
					}

					Call.State.End, Call.State.Error, Call.State.Released -> {
						if (activeCall == call) {
							silence()
							activeCall = null
							val retrying = dialing != null && SystemClock.elapsedRealtime() < dialDeadline
							when (afterSipCallEnds(connected, retrying, talkingOverRtsp = media?.isAnswered == true)) {
								SipCallEnd.Retry -> {
									main.postDelayed(redial, REDIAL_DELAY_MILLIS)
								}

								SipCallEnd.KeepTalking -> {
									dialing = null
								}

								SipCallEnd.End -> {
									connected = false
									dialing = null
									main.removeCallbacks(redial)
									closeCall()
								}
							}
						}
					}

					else -> {}
				}
			}
		}

	private companion object {
		const val RING = "RING"

		const val ANSWERED = "ANSWERED"

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

	private fun intercomFor(call: Call): IntercomConfig? {
		val server = call.remoteAddress.domain
		return applied?.intercoms?.firstOrNull { it.server == server }
	}

	private fun callName(call: Call): String = intercomFor(call)?.name ?: call.remoteAddress.displayName ?: "Intercom"
}

internal enum class SipCallEnd { Retry, KeepTalking, End }

/**
 * What a SIP call that ends leaves. One that got through ends everything: the intercom or the panel hung up. One that did
 * not is tried again while answering lasts. Otherwise only a conversation already going over RTSP goes on: an intercom
 * that rang and gave up, with no one answering, takes its call screen with it.
 */
internal fun afterSipCallEnds(
	connected: Boolean,
	retrying: Boolean,
	talkingOverRtsp: Boolean,
): SipCallEnd =
	when {
		connected -> SipCallEnd.End
		retrying -> SipCallEnd.Retry
		talkingOverRtsp -> SipCallEnd.KeepTalking
		else -> SipCallEnd.End
	}
