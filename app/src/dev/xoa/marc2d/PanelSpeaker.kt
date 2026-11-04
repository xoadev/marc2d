package dev.xoa.marc2d

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlin.math.roundToInt

/** Plays what HA sends, such as the speech it generates with `tts.speak`. */
class PanelSpeaker(
	context: Context,
	private val onPlaying: (Boolean) -> Unit,
	private val onVolume: (Double) -> Unit,
) {
	private val audio = context.getSystemService(AudioManager::class.java)
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

	fun play(url: String) {
		stop()
		player =
			MediaPlayer().apply {
				setAudioAttributes(
					AudioAttributes
						.Builder()
						.setUsage(AudioAttributes.USAGE_MEDIA)
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
						.build(),
				)
				setOnPreparedListener {
					it.start()
					onPlaying(true)
				}
				setOnCompletionListener { stop() }
				setOnErrorListener { _, what, extra ->
					Log.w(TAG, "Could not play the audio: $what, $extra")
					stop()
					true
				}
				try {
					setDataSource(url)
					prepareAsync()
				} catch (e: java.io.IOException) {
					Log.w(TAG, "Could not open the audio: ${e::class.simpleName}")
					stop()
				} catch (e: IllegalArgumentException) {
					Log.w(TAG, "Could not open the audio: ${e::class.simpleName}")
					stop()
				}
			}
	}

	fun stop() {
		player?.release()
		player = null
		onPlaying(false)
	}

	private companion object {
		const val TAG = "marc2d"
	}
}
