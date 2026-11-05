package dev.xoa.marc2d

/** What the droid on the main screen shows. */
enum class DroidMood { Walking, Sleeping, Offline, Listening, Recording, Alarm, Intruder }

/** The states HA sends, with the `select.*_state` entity. */
enum class HaMood(
	val label: String,
) {
	Normal("Normal"),
	Alarm("Alarm"),
	Intruder("Intruder"),
	Recording("Recording"),
	;

	companion object {
		fun fromLabel(label: String): HaMood? = entries.firstOrNull { it.label == label }
	}
}

object DroidMoods {
	/** What HA says overrides what the panel works out by itself: a failure does not hide an alarm. */
	fun resolve(
		ha: HaMood,
		hasProblem: Boolean,
		dark: Boolean,
		listening: Boolean = false,
		cameraOn: Boolean = false,
	): DroidMood =
		when {
			ha == HaMood.Intruder -> DroidMood.Intruder

			ha == HaMood.Alarm -> DroidMood.Alarm

			// Whoever is in front has to know, whatever else the panel would like to say.
			ha == HaMood.Recording || cameraOn -> DroidMood.Recording

			listening -> DroidMood.Listening

			hasProblem -> DroidMood.Offline

			dark -> DroidMood.Sleeping

			else -> DroidMood.Walking
		}
}
