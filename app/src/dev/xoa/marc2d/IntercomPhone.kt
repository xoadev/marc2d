package dev.xoa.marc2d

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import org.linphone.core.Account
import org.linphone.core.AudioDevice
import org.linphone.core.Call
import org.linphone.core.CallParams
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

	/** What carries the open call besides SIP signalling, from its ring until the call screen goes. */
	private var media: CallMedia? = null
	private var videoView: TextureView? = null

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
		_call.value = IntercomCall(name, answered, live = media?.live == true)
	}

	/** The call screen goes, and with it the intercom's media. */
	private fun closeCall() {
		stopMedia()
		_call.value = null
	}

	/** Opens the intercom's media and the panel starts speaking. */
	private fun talk(intercom: IntercomConfig) {
		startMedia(intercom).answer()
		show(intercom.name, answered = true)
	}

	/** The open call's media, or the [intercom]'s, opened now: an unknown caller is SIP only. */
	private fun startMedia(intercom: IntercomConfig?): CallMedia =
		media ?: (intercom?.rtsp?.let { RtspCallMedia(RtspCall(context, it).apply { start() }) } ?: SipCallMedia(core)).also {
			media = it
			it.show(videoView)
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
			startMedia(intercom).configure(params)
			activeCall = core.inviteAddressWithParams(address, params)
		}
		if (activeCall == null) sipCallEnded()
	}

	/** The SIP call ended, or could not be placed. */
	private fun sipCallEnded() {
		// After a button press, the VTO does not answer while its own group call lasts: keep trying.
		val retrying = dialing != null && SystemClock.elapsedRealtime() < dialDeadline
		apply(afterSipCallEnds(connected, retrying, talkingWithoutSip = media?.isTalking == true))
	}

	private fun apply(end: SipCallEnd) {
		when (end) {
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

	/** To talk through the intercom without anyone having rung. */
	fun callIntercom(name: String) {
		if (activeCall != null || dialing != null || media != null) return
		val intercom = applied?.intercoms?.firstOrNull { it.name == name } ?: return
		// Nobody rang: when the conversation goes without SIP there is no call to answer, it is enough to speak.
		if (startMedia(intercom).live) talk(intercom) else dial(intercom)
	}

	fun answer() {
		ringing?.let {
			dial(it)
			return
		}
		val call = activeCall ?: return
		// Hung up already: the call is going away.
		val media = media ?: return
		val params = core.createCallParams(call) ?: return
		media.configure(params)
		media.answer()
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

	/** The call screen's video, which the call's media shows while it lasts. */
	fun setVideoView(view: TextureView?) {
		videoView = view
		media?.show(view)
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
						startMedia(intercomFor(call))
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
							sipCallEnded()
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
 * What a SIP call that ends, or cannot be placed, leaves. One that got through ends everything: the intercom or the panel
 * hung up. One that did not is tried again while answering lasts. Otherwise only a conversation already going without SIP
 * goes on: an intercom that rang and gave up, with no one answering, takes its call screen with it.
 */
internal fun afterSipCallEnds(
	connected: Boolean,
	retrying: Boolean,
	talkingWithoutSip: Boolean,
): SipCallEnd =
	when {
		connected -> SipCallEnd.End
		retrying -> SipCallEnd.Retry
		talkingWithoutSip -> SipCallEnd.KeepTalking
		else -> SipCallEnd.End
	}

/** What carries an intercom's call besides SIP signalling, chosen once from its configuration. */
private interface CallMedia {
	/** Its video and sound come without SIP, from the ring: no SIP call is needed to talk. */
	val live: Boolean

	/** Whether a conversation is going on without SIP, so it outlives the SIP call. */
	val isTalking: Boolean

	/** Someone answered: the panel starts speaking. */
	fun answer()

	/** What the SIP call carries. */
	fun configure(params: CallParams)

	/** Where its video goes, or nothing while there is no call screen. */
	fun show(view: TextureView?)

	fun stop()
}

/** Sound and video in the SIP call. The door station is visible but sees nothing. */
private class SipCallMedia(
	private val core: Core,
) : CallMedia {
	override val live = false
	override val isTalking = false

	override fun answer() = Unit

	override fun configure(params: CallParams) {
		params.isVideoEnabled = true
		params.videoDirection = MediaDirection.RecvOnly
	}

	override fun show(view: TextureView?) {
		core.nativeVideoWindowId = view
	}

	override fun stop() {
		core.nativeVideoWindowId = null
	}
}

/** The intercom's video, sound and the panel's voice over RTSP. SIP only tells the intercom someone answered or hung up. */
private class RtspCallMedia(
	private val call: RtspCall,
) : CallMedia {
	private var surface: Surface? = null

	override val live = true
	override val isTalking get() = call.isAnswered

	override fun answer() = call.answer()

	override fun configure(params: CallParams) {
		params.isVideoEnabled = false
		params.audioDirection = MediaDirection.Inactive
	}

	override fun show(view: TextureView?) {
		release()
		view ?: return
		view.surfaceTexture?.let(::showOn)
		view.surfaceTextureListener =
			object : TextureView.SurfaceTextureListener {
				override fun onSurfaceTextureAvailable(
					texture: SurfaceTexture,
					width: Int,
					height: Int,
				) = showOn(texture)

				override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
					release()
					return true
				}

				override fun onSurfaceTextureSizeChanged(
					texture: SurfaceTexture,
					width: Int,
					height: Int,
				) = Unit

				override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
			}
	}

	override fun stop() = call.stop()

	private fun showOn(texture: SurfaceTexture) {
		surface = Surface(texture).also(call::setSurface)
	}

	private fun release() {
		call.setSurface(null)
		surface?.release()
		surface = null
	}
}
