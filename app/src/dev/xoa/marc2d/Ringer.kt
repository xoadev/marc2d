package dev.xoa.marc2d

/**
 * The panel's ringtone, at most once, however many times something asks for it: a doorbell from HA and the SIP call
 * that follows it would otherwise ring twice.
 */
class Ringer(
	private val play: () -> Sound?,
) {
	fun interface Sound {
		fun stop()
	}

	private var sound: Sound? = null

	val isRinging: Boolean
		get() = sound != null

	fun start() {
		if (sound == null) sound = play()
	}

	fun stop() {
		sound?.stop()
		sound = null
	}
}
