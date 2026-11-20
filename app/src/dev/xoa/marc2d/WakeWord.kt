package dev.xoa.marc2d

import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Decides if someone said the wake phrase. Vosk can only hear what its grammar lists, so with just the phrase it fits
 * nearly any speech into it: "hoy es martes" came out as "oye mark". The other entries give that speech somewhere else to
 * go: general Spanish, not chosen for one phrase, as each panel has its own. Measured with spoken samples, in SPEC.md.
 *
 * A word that is not in the model's vocabulary is ignored by Vosk, and the phrase is then never heard.
 */
class WakeWord(
	phrase: String,
) {
	val phrase: String = normalize(phrase)

	val grammar: String =
		JsonArray(
			(listOf(this.phrase) + DECOYS.filter { it != this.phrase } + "[unk]").map(::JsonPrimitive),
		).toString()

	/**
	 * How sure Vosk is of its least clear word, or nothing if it heard something else. The phrase has to be all that it heard:
	 * inside a longer sentence it is someone talking, not someone calling the panel.
	 */
	fun confidence(result: String): Double? = phraseWords(result)?.map { it.number("conf") ?: return null }?.min()

	/** When the phrase ended, in seconds since the recognizer started: what comes after it is the request. */
	fun end(result: String): Double? = phraseWords(result)?.last()?.number("end")

	/** The words of a Vosk result, only if the phrase is all that it heard. */
	private fun phraseWords(result: String): List<JsonObject>? {
		val json =
			try {
				Json.parseToJsonElement(result) as? JsonObject
			} catch (_: SerializationException) {
				// Vosk writes the result itself, so a malformed one is a glitch of the recognizer: it heard nothing usable.
				null
			} ?: return null
		if ((json["text"] as? JsonPrimitive)?.content != phrase) return null
		return (json["result"] as? JsonArray)?.map { it as? JsonObject ?: return null }?.ifEmpty { null }
	}

	private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

	companion object {
		private const val MIN_CONFIDENCE = 0.9
		private val NOT_IN_A_WORD = Regex("[^\\p{L}\\p{N} ]")
		private val SPACES = Regex(" +")

		private val COMMON =
			(
				"sí no qué que y a de la el los las un una en con por para me te se lo le mi tu es está hay ya voy ven vamos " +
					"mira mirar más menos muy bien mal hoy ayer mañana ahora luego casa hora día oye oigo oyes hola adiós martes lunes"
			).split(" ")
		private val NAMES_AND_SOUNDS =
			(
				"mar marco marca marcha maría marta mario mamá papá javier jaime jorge josé juan davis travis marvin kevin " +
					"viste vino vi vis jardín jarra yate paris carlos ana luis"
			).split(" ")
		private val AFTER_OYE = "maría marta mario marcos mamá más mañana mar mira qué tú".split(" ").map { "oye $it" }
		private val DECOYS = (COMMON + NAMES_AND_SOUNDS + AFTER_OYE).distinct()

		/** Vosk's words are in lower case and without punctuation, and the form may have anything. */
		fun normalize(phrase: String): String =
			phrase
				.lowercase(Locale.ROOT)
				.replace(NOT_IN_A_WORD, "")
				.replace(SPACES, " ")
				.trim()
				.ifEmpty { VoiceConfig.DEFAULT_PHRASE }

		fun isEnough(confidence: Double?): Boolean = confidence != null && confidence >= MIN_CONFIDENCE
	}
}
