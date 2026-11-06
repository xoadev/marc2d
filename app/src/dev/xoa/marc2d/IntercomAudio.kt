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
import java.net.InetSocketAddress
import java.net.StandardSocketOptions

/**
 * The microphone and the speaker for an intercom whose audio Linphone cannot play, through [PcmRtpLink]. Started and stopped
 * from the main thread; the audio runs on two threads of its own.
 */
class IntercomAudio(
	private val context: Context,
) {
	private var socket: DatagramSocket? = null
	private var threads: List<Thread> = emptyList()

	@Volatile private var running = false

	@SuppressLint("MissingPermission") // The service only starts with the microphone permission.
	fun start(
		port: Int,
		format: PcmFormat,
		negotiatedPayloadType: Int,
	) {
		if (socket != null) return
		val shared =
			try {
				DatagramSocket(null).apply {
					reuseAddress = true
					setOption(StandardSocketOptions.SO_REUSEPORT, true)
					bind(InetSocketAddress(port))
				}
			} catch (e: Exception) {
				Log.w(TAG, "Intercom audio: no port ${e::class.simpleName}")
				return
			}
		socket = shared
		running = true
		useSpeaker()
		val track = speaker(format)
		val link = PcmRtpLink(shared, format, negotiatedPayloadType) { track.write(it, 0, it.size) }
		threads =
			listOf(
				Thread({ runSafely("speaker") { link.receive() } }, "marc2d-intercom-in"),
				Thread({ runSafely("microphone") { record(link, format) } }, "marc2d-intercom-out"),
			)
		threads.forEach(Thread::start)
		Thread({
			threads.forEach(Thread::join)
			track.release()
		}, "marc2d-intercom-end").start()
	}

	fun stop() {
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

	@SuppressLint("MissingPermission")
	private fun record(
		link: PcmRtpLink,
		format: PcmFormat,
	) {
		val minimum = AudioRecord.getMinBufferSize(format.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
		// The communication source gets Android's processing for calls, with the echo of the speaker taken out where it can.
		val microphone =
			AudioRecord(
				MediaRecorder.AudioSource.VOICE_COMMUNICATION,
				format.sampleRate,
				AudioFormat.CHANNEL_IN_MONO,
				AudioFormat.ENCODING_PCM_16BIT,
				maxOf(minimum, format.frameSamples * 2 * BUFFERED_FRAMES),
			)
		val effects =
			listOfNotNull(
				if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(microphone.audioSessionId) else null,
				if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(microphone.audioSessionId) else null,
			)
		effects.forEach { it.enabled = true }
		try {
			microphone.startRecording()
			val frame = ShortArray(format.frameSamples)
			while (running) {
				var read = 0
				while (read < frame.size && running) {
					val got = microphone.read(frame, read, frame.size - read)
					if (got < 0) return
					read += got
				}
				// Until the intercom has spoken, it is not known where to send it.
				if (running) link.send(frame)
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
	}
}
