package dev.xoa.marc2d

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A call with an intercom over RTSP. It opens as soon as it rings, to show who is at the door; on answering, the street is
 * heard and the panel's voice goes through the backchannel. SIP, if any, only tells the intercom someone answered. Started
 * and stopped from the main thread; the session runs on threads of its own.
 */
class RtspCall(
	private val context: Context,
	private val url: String,
) {
	@Volatile private var running = true

	@Volatile private var answered = false

	@Volatile private var session: RtspSession? = null

	@Volatile private var surface: Surface? = null
	private val video = VideoDecoder()
	private var speaker: AudioTrack? = null

	fun start() {
		Thread({ receive() }, "marc2d-rtsp-in").start()
	}

	/** Where the video goes, or nothing while there is no screen for it. */
	fun setSurface(surface: Surface?) {
		this.surface = surface
	}

	/** The street is heard, and the panel speaks. */
	fun answer() {
		if (answered || !running) return
		answered = true
		val manager = context.getSystemService(AudioManager::class.java)
		manager.mode = AudioManager.MODE_IN_COMMUNICATION
		useSpeaker(context)
		Thread({ speak() }, "marc2d-rtsp-out").start()
	}

	fun stop() {
		if (!running) return
		running = false
		val open = session
		// The teardown is network work: not on the main thread.
		Thread({ open?.close() }, "marc2d-rtsp-close").start()
		if (answered) {
			val manager = context.getSystemService(AudioManager::class.java)
			manager.clearCommunicationDevice()
			manager.mode = AudioManager.MODE_NORMAL
		}
	}

	private fun receive() {
		val opened =
			try {
				RtspSession.open(url)
			} catch (e: IOException) {
				// Never the URL: it carries the password.
				Log.w(TAG, "Intercom RTSP not opened: ${e.message}")
				return
			}
		session = opened
		if (!running) {
			opened.close()
			return
		}
		val offer = opened.offer
		Log.i(TAG, "Intercom RTSP: video ${offer.video != null}, sound ${offer.sound?.codec}, voice ${offer.voice?.codec}")
		val depacketizer = H264Depacketizer()
		try {
			opened.receive { media, rtp, data ->
				if (!running) throw IOException("Stopped")
				when (media) {
					RtspMedia.Video -> depacketizer.push(rtp, data)?.let { video.decode(it, rtp.timestamp, offer.video?.parameters, surface) }
					RtspMedia.Sound -> if (answered) offer.sound?.let { play(it, it.decode(data, rtp.payloadOffset, rtp.payloadLength)) }
				}
			}
		} catch (e: Exception) {
			if (running) Log.w(TAG, "Intercom RTSP stopped: ${e::class.simpleName}")
		} finally {
			video.release()
			speaker?.release()
			speaker = null
		}
	}

	private fun play(
		track: RtspAudioTrack,
		samples: ShortArray,
	) {
		val out = speaker ?: createSpeaker(track.sampleRate).also { speaker = it }
		// Never blocking: the video comes on the same thread.
		out.write(samples, 0, samples.size, AudioTrack.WRITE_NON_BLOCKING)
	}

	private fun createSpeaker(sampleRate: Int): AudioTrack {
		val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
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
					.setSampleRate(sampleRate)
					.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
					.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
					.build(),
			)
			// Enough for the network's ups and downs, little enough not to delay the conversation.
			.setBufferSizeInBytes(maxOf(minimum, sampleRate * 2 * SPEAKER_BUFFER_MILLIS / 1000))
			.setTransferMode(AudioTrack.MODE_STREAM)
			.build()
			.apply { play() }
	}

	/** On its own thread, once answered: the microphone into the backchannel. */
	private fun speak() {
		try {
			// The session may still be opening when answering right away, as with Talk.
			while (running && session == null) Thread.sleep(WAIT_MILLIS)
			val open = session ?: return
			val voice = open.offer.voice
			if (voice == null) {
				Log.w(TAG, "Intercom RTSP has no backchannel")
				return
			}
			recordMicrophone(voice.sampleRate, { running }) { open.sendVoice(it) }
		} catch (e: Exception) {
			if (running) Log.w(TAG, "Intercom RTSP voice stopped: ${e::class.simpleName}")
		}
	}

	private companion object {
		const val TAG = "marc2d"
		const val SPEAKER_BUFFER_MILLIS = 160
		const val WAIT_MILLIS = 50L
	}
}

/**
 * Decodes H.264 access units onto a surface, with the hardware decoder. Until the surface is there, or after it changes,
 * it waits for the next key frame: the access units since the last one are kept to start at once.
 */
private class VideoDecoder {
	private var codec: MediaCodec? = null
	private var shownOn: Surface? = null
	private val sinceKeyFrame = ArrayDeque<ByteArray>()
	private val info = MediaCodec.BufferInfo()

	fun decode(
		unit: ByteArray,
		timestamp: Long,
		parameters: H264Parameters?,
		surface: Surface?,
	) {
		if (H264Depacketizer.isKeyFrame(unit)) sinceKeyFrame.clear()
		if (sinceKeyFrame.size < MAX_KEPT) sinceKeyFrame.addLast(unit)
		if (surface != shownOn) {
			release()
			shownOn = surface
			if (surface != null) {
				codec = create(surface, parameters)
				// From the last key frame, so the picture appears at once.
				sinceKeyFrame.dropLast(1).forEach { feed(it, timestamp) }
			}
		}
		feed(unit, timestamp)
	}

	private fun create(
		surface: Surface,
		parameters: H264Parameters?,
	): MediaCodec? =
		try {
			val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, MAX_WIDTH, MAX_HEIGHT)
			parameters?.let {
				format.setByteBuffer("csd-0", ByteBuffer.wrap(H264Depacketizer.START_CODE + it.sps))
				format.setByteBuffer("csd-1", ByteBuffer.wrap(H264Depacketizer.START_CODE + it.pps))
			}
			MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
				configure(format, surface, null, 0)
				start()
			}
		} catch (e: Exception) {
			Log.w("marc2d", "Intercom video decoder: ${e::class.simpleName}")
			null
		}

	private fun feed(
		unit: ByteArray,
		timestamp: Long,
	) {
		val decoder = codec ?: return
		try {
			val index = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
			if (index >= 0) {
				decoder.getInputBuffer(index)?.apply {
					clear()
					put(unit)
				}
				decoder.queueInputBuffer(index, 0, unit.size, timestamp * MICROS / VIDEO_CLOCK, 0)
			}
			while (true) {
				val out = decoder.dequeueOutputBuffer(info, 0)
				if (out >= 0) {
					decoder.releaseOutputBuffer(out, true)
				} else if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
					break
				}
			}
		} catch (e: IllegalStateException) {
			// A surface gone from under it: the next one starts a new decoder.
			release()
		}
	}

	fun release() {
		codec?.let {
			try {
				it.stop()
			} catch (_: IllegalStateException) {
				// Already stopped.
			}
			it.release()
		}
		codec = null
		shownOn = null
	}

	private companion object {
		const val MAX_WIDTH = 1920
		const val MAX_HEIGHT = 1080
		const val MAX_KEPT = 120
		const val INPUT_TIMEOUT_US = 10_000L
		const val MICROS = 1_000_000L
		const val VIDEO_CLOCK = 90_000L
	}
}
