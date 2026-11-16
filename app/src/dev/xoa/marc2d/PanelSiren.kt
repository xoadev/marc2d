package dev.xoa.marc2d

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration.Companion.minutes

/** Sounds the siren on the alarm channel, at full volume. */
class PanelSiren(
	context: Context,
	private val onChange: (Boolean) -> Unit,
) {
	private val audio = context.getSystemService(AudioManager::class.java)
	private val main = Handler(Looper.getMainLooper())
	private var track: AudioTrack? = null
	private var previousVolume = 0

	// If the connection is lost while it sounds, HA could no longer turn it off.
	private val safetyStop = Runnable { set(false) }

	fun set(on: Boolean) {
		if (on == (track != null)) {
			onChange(on)
			return
		}
		if (on) start() else stop()
		onChange(on)
	}

	private fun start() {
		previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
		audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
		val samples = tone()
		track =
			monoPcmTrack(
				AudioAttributes.USAGE_ALARM,
				AudioAttributes.CONTENT_TYPE_SONIFICATION,
				SAMPLE_RATE,
				AudioTrack.MODE_STATIC,
				samples.size * Short.SIZE_BYTES,
			).apply {
				write(samples, 0, samples.size)
				setLoopPoints(0, samples.size, -1)
				play()
			}
		main.postDelayed(safetyStop, MAX_DURATION.inWholeMilliseconds)
	}

	private fun stop() {
		main.removeCallbacks(safetyStop)
		track?.run {
			stop()
			release()
		}
		track = null
		audio.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
	}

	/** Half a second on each tone, with no libraries or audio files. */
	private fun tone(): ShortArray =
		ShortArray(SAMPLE_RATE) { i ->
			val frequency = if (i < SAMPLE_RATE / 2) LOW_HZ else HIGH_HZ
			(sin(2 * PI * frequency * i / SAMPLE_RATE) * Short.MAX_VALUE).toInt().toShort()
		}

	private companion object {
		const val SAMPLE_RATE = 44_100
		const val LOW_HZ = 800.0
		const val HIGH_HZ = 1_200.0
		val MAX_DURATION = 10.minutes
	}
}

/** A mono 16-bit PCM track, not yet playing. */
internal fun monoPcmTrack(
	usage: Int,
	contentType: Int,
	sampleRate: Int,
	mode: Int,
	bufferBytes: Int,
): AudioTrack =
	AudioTrack
		.Builder()
		.setAudioAttributes(
			AudioAttributes
				.Builder()
				.setUsage(usage)
				.setContentType(contentType)
				.build(),
		).setAudioFormat(
			AudioFormat
				.Builder()
				.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
				.setSampleRate(sampleRate)
				.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
				.build(),
		).setTransferMode(mode)
		.setBufferSizeInBytes(bufferBytes)
		.build()
