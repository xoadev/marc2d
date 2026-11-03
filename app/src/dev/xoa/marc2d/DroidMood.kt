package dev.xoa.marc2d

/** Lo que muestra el droide de la pantalla principal. */
enum class DroidMood { Walking, Sleeping, Offline, Recording, Alarm, Intruder }

/** Los estados que HA manda por MQTT, con la entidad `select.*_estado`. */
enum class HaMood(
	val label: String,
) {
	Normal("Normal"),
	Alarm("Alarma"),
	Intruder("Intruso"),
	Recording("Grabando"),
	;

	companion object {
		fun fromLabel(label: String): HaMood? = entries.firstOrNull { it.label == label }
	}
}

object DroidMoods {
	/** Lo que dice HA manda sobre lo que el panel deduce solo: una alarma no se oculta por un fallo. */
	fun resolve(
		ha: HaMood,
		hasProblem: Boolean,
		dark: Boolean,
	): DroidMood =
		when {
			ha == HaMood.Intruder -> DroidMood.Intruder
			ha == HaMood.Alarm -> DroidMood.Alarm
			ha == HaMood.Recording -> DroidMood.Recording
			hasProblem -> DroidMood.Offline
			dark -> DroidMood.Sleeping
			else -> DroidMood.Walking
		}
}
