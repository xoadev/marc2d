package dev.xoa.marc2d

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A failed read does not recover by itself, as when the audio server restarts, and retrying at once would spin at full CPU. */
fun microphoneBytes(read: Int): Int {
	if (read <= 0) throw IOException("The microphone returned $read")
	return read
}

/** Hears everything locally. Audio leaves the panel only after the wake phrase, through [onWake]. */
class VoiceListener(
	private val model: VoiceModel,
	private val onWake: (AssistRun) -> Unit,
	private val onListening: (Boolean) -> Unit,
) {
	/** Runs until cancelled: cancelling it releases the microphone, as a call needs. */
	@SuppressLint("MissingPermission") // The service only starts with the microphone permission.
	suspend fun run(
		pipeline: String?,
		phrase: String,
	) =
		withContext(Dispatchers.IO) {
			model.ensure()
			val buffer = ByteArray(BUFFER_BYTES)
			val wakeWord = WakeWord(phrase)
			val history = AudioHistory(HISTORY_BYTES)
			Model(model.path.path).use { vosk ->
				Recognizer(vosk, SAMPLE_RATE.toFloat(), wakeWord.grammar).use { recognizer ->
					// Without the words, Vosk does not say how sure it is.
					recognizer.setWords(true)
					// Opened last, so that a model that fails to load does not leave it behind on each retry.
					val record =
						AudioRecord(
							MediaRecorder.AudioSource.VOICE_RECOGNITION,
							SAMPLE_RATE,
							AudioFormat.CHANNEL_IN_MONO,
							AudioFormat.ENCODING_PCM_16BIT,
							maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), BUFFER_BYTES * 4),
						)
					try {
						record.startRecording()
						while (true) {
							currentCoroutineContext().ensureActive()
							val read = microphoneBytes(record.read(buffer, 0, buffer.size))
							history.add(buffer, read)
							if (recognizer.acceptWaveForm(buffer, read)) {
								val result = recognizer.result
								val confidence = wakeWord.confidence(result)
								// Only the phrase's number, never what was heard: it is what real voices tell us about the threshold.
								if (confidence != null) Log.i(TAG, "Wake phrase at $confidence")
								if (WakeWord.isEnough(confidence)) {
									val end = wakeWord.end(result)
									val already = end?.let { history.since((it * SAMPLE_RATE).toLong() * BYTES_PER_SAMPLE) } ?: ByteArray(0)
									streamRequest(record, buffer, pipeline, already)
									recognizer.reset()
								}
							}
						}
					} finally {
						// It also stops the recording, and unlike stop() it does not throw if that never started.
						record.release()
					}
				}
			}
		}

	private suspend fun streamRequest(
		record: AudioRecord,
		buffer: ByteArray,
		pipeline: String?,
		already: ByteArray,
	) {
		val audio = Channel<ByteArray>(Channel.UNLIMITED)
		// What was said while Vosk waited for the silence that confirms the phrase.
		if (already.isNotEmpty()) audio.trySend(amplify(already, REQUEST_GAIN))
		val finished = Channel<Unit>(Channel.CONFLATED)
		onListening(true)
		onWake(AssistRun(pipeline, audio, onStopListening = { finished.trySend(Unit) }))
		val started = TimeSource.Monotonic.markNow()
		try {
			while (started.elapsedNow() < MAX_REQUEST && finished.tryReceive().isFailure) {
				currentCoroutineContext().ensureActive()
				audio.trySend(amplify(buffer.copyOf(microphoneBytes(record.read(buffer, 0, buffer.size))), REQUEST_GAIN))
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
		const val BYTES_PER_SAMPLE = 2

		// Only for what goes to Assist: the wake phrase was measured as it comes. Four times, 12 dB, takes the voice from about
		// -30 to -18 dBFS, and the room's noise stays well below it.
		const val REQUEST_GAIN = 4

		// Vosk confirms the phrase about a second after it ends: twice that is kept.
		const val HISTORY_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * 2
		val MAX_REQUEST = 10.seconds
	}
}
