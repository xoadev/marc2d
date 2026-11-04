package dev.xoa.marc2d

import android.media.AudioAttributes
import android.media.MediaPlayer

/** Plays what HA sends, such as the speech it generates with `tts.speak`. */
class PanelSpeaker(
	private val onPlaying: (Boolean) -> Unit,
) {
	private var player: MediaPlayer? = null

	fun play(url: String) {
		stop()
		player =
			MediaPlayer().apply {
				setAudioAttributes(
					AudioAttributes
						.Builder()
						.setUsage(AudioAttributes.USAGE_ASSISTANT)
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
						.build(),
				)
				setOnPreparedListener {
					it.start()
					onPlaying(true)
				}
				setOnCompletionListener { stop() }
				setOnErrorListener { _, _, _ ->
					stop()
					true
				}
				try {
					setDataSource(url)
					prepareAsync()
				} catch (_: java.io.IOException) {
					stop()
				} catch (_: IllegalArgumentException) {
					stop()
				}
			}
	}

	fun stop() {
		player?.release()
		player = null
		onPlaying(false)
	}
}
