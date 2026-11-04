package dev.xoa.marc2d

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Hears everything locally. Audio leaves the panel only after the wake phrase, through [onWake]. */
class VoiceListener(
	private val model: VoiceModel,
	private val onWake: (AssistRun) -> Unit,
	private val onListening: (Boolean) -> Unit,
) {
	/** Runs until cancelled: cancelling it releases the microphone, as a call needs. */
	@SuppressLint("MissingPermission") // The service only starts with the microphone permission.
	suspend fun run(pipeline: String?) =
		withContext(Dispatchers.IO) {
			model.ensure()
			val buffer = ByteArray(BUFFER_BYTES)
			val record =
				AudioRecord(
					MediaRecorder.AudioSource.VOICE_RECOGNITION,
					SAMPLE_RATE,
					AudioFormat.CHANNEL_IN_MONO,
					AudioFormat.ENCODING_PCM_16BIT,
					maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), BUFFER_BYTES * 4),
				)
			Model(model.path.path).use { vosk ->
				Recognizer(vosk, SAMPLE_RATE.toFloat(), WakeWord.GRAMMAR).use { recognizer ->
					try {
						record.startRecording()
						while (true) {
							currentCoroutineContext().ensureActive()
							val read = record.read(buffer, 0, buffer.size)
							if (read <= 0) continue
							if (recognizer.acceptWaveForm(buffer, read) && WakeWord.heard(recognizer.result)) {
								Log.i(TAG, "Wake phrase heard")
								streamRequest(record, buffer, pipeline)
								recognizer.reset()
							}
						}
					} finally {
						record.stop()
						record.release()
					}
				}
			}
		}

	private suspend fun streamRequest(
		record: AudioRecord,
		buffer: ByteArray,
		pipeline: String?,
	) {
		val audio = Channel<ByteArray>(Channel.UNLIMITED)
		val finished = Channel<Unit>(Channel.CONFLATED)
		onListening(true)
		onWake(AssistRun(pipeline, audio, onStopListening = { finished.trySend(Unit) }))
		val started = TimeSource.Monotonic.markNow()
		try {
			while (started.elapsedNow() < MAX_REQUEST && finished.tryReceive().isFailure) {
				currentCoroutineContext().ensureActive()
				val read = record.read(buffer, 0, buffer.size)
				if (read > 0) audio.trySend(buffer.copyOf(read))
			}
		} catch (e: CancellationException) {
			throw e
		} finally {
			audio.close()
			onListening(false)
		}
	}

	private companion object {
		const val TAG = "marc2d"
		const val SAMPLE_RATE = 16_000
		const val BUFFER_BYTES = 3_200
		val MAX_REQUEST = 10.seconds
	}
}
