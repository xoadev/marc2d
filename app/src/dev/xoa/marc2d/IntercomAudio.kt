package dev.xoa.marc2d

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.net.DatagramSocket

/**
 * The microphone and the speaker for an intercom whose audio Linphone cannot play, through [PcmRtpLink], and the panel's
 * voice when it goes over RTSP, through [RtspBackchannel]. Started and stopped from the main thread; the audio runs on
 * threads of its own.
 */
class IntercomAudio(
	private val context: Context,
) {
	private var socket: DatagramSocket? = null
	private var threads: List<Thread> = emptyList()
	private var link: PcmRtpLink? = null
	private var track: AudioTrack? = null

	@Volatile private var running = false

	/**
	 * [format] is the intercom's own audio, which the panel plays and, without [talk], also sends: nothing when Linphone plays
	 * it. [talk] is the RTSP URL the panel's voice goes to instead.
	 */
	fun start(
		port: Int,
		format: PcmFormat?,
		negotiatedPayloadType: Int,
		talk: String?,
	) {
		if (running) return
		running = true
		useSpeaker()
		val link =
			format?.let {
				try {
					takeOverUdpPort(port)
				} catch (e: Exception) {
					Log.w(TAG, "Intercom audio: no port ${e::class.simpleName}")
					null
				}?.let { shared ->
					socket = shared
					val track = speaker(format)
					this.track = track
					PcmRtpLink(shared, format, negotiatedPayloadType) { track.write(it, 0, it.size) }
				}
			}
		this.link = link
		Log.i(TAG, "Intercom audio on port $port, apart from type $negotiatedPayloadType, talking over ${if (talk != null) "RTSP" else "RTP"}")
		val speaking =
			when {
				talk != null -> Thread({ runSafely("microphone") { talkOverRtsp(talk) } }, "marc2d-intercom-out")
				link != null -> Thread({ runSafely("microphone") { record(link.format.sampleRate) { link.send(it) } } }, "marc2d-intercom-out")
				else -> null
			}
		threads = listOfNotNull(link?.let { Thread({ runSafely("speaker") { it.receive() } }, "marc2d-intercom-in") }, speaking)
		threads.forEach(Thread::start)
		val playing = track
		Thread({
			threads.forEach(Thread::join)
			playing?.release()
		}, "marc2d-intercom-end").start()
	}

	/** On the microphone's thread: the connection is network work, and it ends when the recording does. */
	private fun talkOverRtsp(url: String) {
		val backchannel =
			try {
				RtspBackchannel.open(url)
			} catch (e: java.io.IOException) {
				// Never the URL: it carries the password.
				Log.w(TAG, "Intercom backchannel not opened: ${e.message}")
				return
			}
		try {
			Log.i(TAG, "Intercom backchannel: ${backchannel.track.codec}/${backchannel.track.sampleRate}")
			record(backchannel.track.sampleRate) { backchannel.send(it) }
		} finally {
			backchannel.close()
		}
	}

	fun stop() {
		if (!running) return
		link?.let { Log.i(TAG, "Intercom audio: ${it.summary}; speaker buffer ${track?.bufferSizeInFrames} frames, ${track?.underrunCount} underruns") }
		link = null
		track = null
		running = false
		socket?.close()
		socket = null
		context.getSystemService(AudioManager::class.java).clearCommunicationDevice()
	}

	private fun speaker(format: PcmFormat): AudioTrack {
		val minimum = AudioTrack.getMinBufferSize(format.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
		return AudioTrack
			.Builder()
			.setAudioAttributes(
				AudioAttributes
					.Builder()
					.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
					.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
					.build(),
			).setAudioFormat(
				AudioFormat
					.Builder()
					.setSampleRate(format.sampleRate)
					.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
					.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
					.build(),
			)
			// A few packets: enough for the network's ups and downs, little enough not to delay the conversation.
			.setBufferSizeInBytes(maxOf(minimum, format.frameSamples * 2 * BUFFERED_FRAMES))
			.setTransferMode(AudioTrack.MODE_STREAM)
			.build()
			.apply { play() }
	}

	@SuppressLint("MissingPermission") // The service only starts with the microphone permission.
	private fun record(
		sampleRate: Int,
		send: (ShortArray) -> Unit,
	) {
		// 20 ms at a time.
		val frameSamples = sampleRate / FRAMES_PER_SECOND
		val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
		// The communication source gets Android's processing for calls, with the echo of the speaker taken out where it can.
		val microphone =
			AudioRecord(
				MediaRecorder.AudioSource.VOICE_COMMUNICATION,
				sampleRate,
				AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT,
				maxOf(minimum, frameSamples * 2 * BUFFERED_FRAMES),
			)
		val effects =
			listOfNotNull(
				if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(microphone.audioSessionId) else null,
				if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(microphone.audioSessionId) else null,
			)
		effects.forEach { it.enabled = true }
		try {
			microphone.startRecording()
			val frame = ShortArray(frameSamples)
			while (running) {
				var read = 0
				while (read < frame.size && running) {
					val got = microphone.read(frame, read, frame.size - read)
					if (got < 0) return
					read += got
				}
				if (running) send(frame)
			}
		} finally {
			effects.forEach { it.release() }
			microphone.release()
		}
	}

	private fun useSpeaker() {
		val manager = context.getSystemService(AudioManager::class.java)
		manager.availableCommunicationDevices
			.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
			?.let(manager::setCommunicationDevice)
	}

	private fun runSafely(
		what: String,
		block: () -> Unit,
	) {
		try {
			block()
		} catch (e: Exception) {
			// On these threads an exception would take the whole panel down.
			if (running) Log.w(TAG, "Intercom audio $what stopped: ${e::class.simpleName}")
		}
	}

	private companion object {
		const val TAG = "marc2d"
		const val BUFFERED_FRAMES = 6
		const val FRAMES_PER_SECOND = 50
	}
}
