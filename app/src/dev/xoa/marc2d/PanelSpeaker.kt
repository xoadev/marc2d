package dev.xoa.marc2d

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.isSuccess
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Plays what HA sends, such as the speech it generates with `tts.speak`. */
class PanelSpeaker(
	context: Context,
	private val scope: CoroutineScope,
	private val onPlaying: (Boolean) -> Unit,
	private val onVolume: (Double) -> Unit,
) {
	private val audio = context.getSystemService(AudioManager::class.java)
	private val file = File(context.cacheDir, "speaker-audio")
	private val client = HttpClient(OkHttp)
	private var job: Job? = null
	private var player: MediaPlayer? = null

	init {
		reportVolume()
	}

	/** From 0 to 1, as HA uses it. */
	fun setVolume(level: Double) {
		val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
		audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0.0, 1.0) * max).roundToInt(), 0)
		reportVolume()
	}

	private fun reportVolume() {
		onVolume(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
	}

	/** Downloads first: MediaPlayer hung on HA's URL, then repeated its error without end at full CPU. */
	fun play(url: String) {
		stop()
		job =
			scope.launch {
				try {
					download(url)
					start()
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					Log.w(TAG, "Could not get the audio: ${e::class.simpleName}")
					stop()
				}
			}
	}

	fun stop() {
		job?.cancel()
		job = null
		release()
		onPlaying(false)
	}

	private suspend fun download(url: String) {
		withTimeout(DOWNLOAD_TIMEOUT) {
			val response = client.get(url)
			if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value}")
			val bytes = response.bodyAsBytes()
			withContext(Dispatchers.IO) { file.writeBytes(bytes) }
		}
	}

	private fun start() {
		player =
			MediaPlayer().apply {
				setAudioAttributes(
					AudioAttributes
						.Builder()
						.setUsage(AudioAttributes.USAGE_MEDIA)
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
						.build(),
				)
				setOnCompletionListener { stop() }
				setOnErrorListener { _, what, extra ->
					Log.w(TAG, "Could not play the audio: $what, $extra")
					stop()
					true
				}
				setDataSource(file.path)
				prepare()
				start()
			}
		onPlaying(true)
	}

	private fun release() {
		player?.apply {
			// Nothing may call back once released: that is what kept repeating the error.
			setOnCompletionListener(null)
			setOnErrorListener(null)
			release()
		}
		player = null
	}

	private companion object {
		const val TAG = "marc2d"
		val DOWNLOAD_TIMEOUT = 30.seconds
	}
}
