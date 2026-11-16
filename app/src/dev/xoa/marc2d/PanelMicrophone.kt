package dev.xoa.marc2d

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** The microphone encoded in AAC, only while [start] and [stop] say so. Its clock is the same as the camera's. */
class PanelMicrophone(
	private val context: Context,
) : AacSource {
	@Volatile private var session: Session? = null

	override suspend fun start(
		onFrame: (AacFrame) -> Unit,
		onFailure: () -> Unit,
	): AacConfig {
		if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
			throw IOException("No microphone permission")
		}
		val started = Session(onFrame, onFailure)
		session = started
		return startWithin(START_TIMEOUT, "microphone", ::stop) { started.open() }
	}

	override fun stop() {
		session?.close()
		session = null
	}

	private class Session(
		private val onFrame: (AacFrame) -> Unit,
		private val onFailure: () -> Unit,
	) : MediaCodec.Callback() {
		private val thread = HandlerThread("marc2d-microphone").apply { start() }
		private val handler = Handler(thread.looper)
		private val config = CompletableDeferred<AacConfig>()
		private var record: AudioRecord? = null
		private var encoder: MediaCodec? = null
		private var closed = false

		suspend fun open(): AacConfig {
			handler.post {
				try {
					val microphone = monoMicrophone(MediaRecorder.AudioSource.CAMCORDER, SAMPLE_RATE, FRAME_BYTES * 8)
					record = microphone
					check(microphone.state == AudioRecord.STATE_INITIALIZED) { "The microphone is not available" }
					microphone.startRecording()
					encoder =
						MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
							setCallback(this@Session, handler)
							configure(
								MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
									setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
									setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
									setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES)
								},
								null,
								null,
								MediaCodec.CONFIGURE_FLAG_ENCODE,
							)
							start()
						}
				} catch (e: RuntimeException) {
					fail(e)
				}
			}
			return config.await()
		}

		override fun onInputBufferAvailable(
			codec: MediaCodec,
			index: Int,
		) {
			if (closed) return
			val buffer = codec.getInputBuffer(index) ?: return
			val read = record?.read(buffer, minOf(buffer.remaining(), FRAME_BYTES)) ?: return
			if (read < 0) {
				fail(IOException("The microphone stopped"))
				return
			}
			// What was just read started that long ago.
			val timestampUs = System.nanoTime() / NANOS_PER_MICRO - read / BYTES_PER_SAMPLE * MICROS_PER_SECOND / SAMPLE_RATE
			codec.queueInputBuffer(index, 0, read, timestampUs, 0)
		}

		override fun onOutputBufferAvailable(
			codec: MediaCodec,
			index: Int,
			info: MediaCodec.BufferInfo,
		) {
			if (closed) return
			codec.takeOutput(index, info) { data ->
				if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
					config.complete(AacConfig(SAMPLE_RATE, 1, data))
				} else {
					onFrame(AacFrame(data, info.presentationTimeUs))
				}
			}
		}

		override fun onError(
			codec: MediaCodec,
			e: MediaCodec.CodecException,
		) = fail(e)

		override fun onOutputFormatChanged(
			codec: MediaCodec,
			format: MediaFormat,
		) = Unit

		private fun fail(cause: Exception) {
			if (!closed) config.failStartOr(cause, onFailure)
		}

		fun close() {
			handler.post {
				closed = true
				// The errors from here on only say that something was already closing.
				runCatching { encoder?.stop() }
				runCatching { encoder?.release() }
				runCatching { record?.release() }
				thread.quitSafely()
			}
		}
	}

	private companion object {
		val START_TIMEOUT = 5.seconds
		const val SAMPLE_RATE = 16_000
		const val BITRATE = 32_000
		const val BYTES_PER_SAMPLE = 2

		// AAC encodes 1024 samples per frame.
		const val FRAME_BYTES = 1024 * BYTES_PER_SAMPLE
		const val NANOS_PER_MICRO = 1000L
	}
}

/** Mono 16-bit PCM from [source], with at least [minBytes] of buffer. */
@SuppressLint("MissingPermission") // The service only starts with the microphone permission.
internal fun monoMicrophone(
	source: Int,
	sampleRate: Int,
	minBytes: Int,
): AudioRecord =
	AudioRecord(
		source,
		sampleRate,
		AudioFormat.CHANNEL_IN_MONO,
		AudioFormat.ENCODING_PCM_16BIT,
		maxOf(AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), minBytes),
	)

/** Starts the camera or the microphone, and stops it again if that fails or takes longer than [timeout]. */
internal suspend fun <T> startWithin(
	timeout: Duration,
	what: String,
	stop: () -> Unit,
	start: suspend () -> T,
): T =
	try {
		withTimeout(timeout) { start() }
	} catch (e: TimeoutCancellationException) {
		stop()
		// Not a cancellation of whoever called: the device just did not answer.
		throw IOException("The $what did not start", e)
	} catch (e: Exception) {
		// A cancellation as well: what was half started has to go either way.
		stop()
		throw e
	}

/** What an encoder produced, copied out of its buffer, which goes back to it right after. */
internal inline fun MediaCodec.takeOutput(
	index: Int,
	info: MediaCodec.BufferInfo,
	onData: (ByteArray) -> Unit,
) {
	val buffer = getOutputBuffer(index)
	if (buffer != null && info.size > 0) {
		val data = ByteArray(info.size)
		buffer.position(info.offset)
		buffer.get(data)
		onData(data)
	}
	releaseOutputBuffer(index, false)
}

/** Before the encoder's first output, a failure is the start's; after it, it is the stream's, which [onFailure] reports. */
internal fun CompletableDeferred<*>.failStartOr(
	cause: Exception,
	onFailure: () -> Unit,
) {
	if (!completeExceptionally(cause)) onFailure()
}
