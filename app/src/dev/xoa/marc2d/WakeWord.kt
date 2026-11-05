package dev.xoa.marc2d

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decides if someone said the wake phrase. Vosk can only hear what its grammar lists, so with just the phrase it fits
 * nearly any speech into it: "hoy es martes" came out as "oye mark". The other entries give that speech somewhere else to
 * go, and they are general Spanish plus what sounds closest. Measured with spoken samples, in SPEC.md.
 */
object WakeWord {
	private const val PHRASE = "oye mark"
	private const val MIN_CONFIDENCE = 0.9

	private val COMMON =
		(
			"sí no qué que y a de la el los las un una en con por para me te se lo le mi tu es está hay ya voy ven vamos " +
				"mira mirar más menos muy bien mal hoy ayer mañana ahora luego casa hora día oye oigo oyes hola adiós martes lunes"
		).split(" ")
	private val CLOSEST = listOf("mar", "marco", "marcos", "marca", "marcha", "maría", "marta", "mario", "mamá", "mark")
	private val AFTER_OYE = listOf("maría", "marta", "mario", "marcos", "mamá", "más", "mañana", "mar", "mira", "qué", "tú").map { "oye $it" }

	val GRAMMAR: String =
		JsonArray((listOf(PHRASE) + COMMON + CLOSEST + AFTER_OYE + "[unk]").map(::JsonPrimitive)).toString()

	/**
	 * How sure Vosk is of its least clear word, or nothing if it heard something else. The phrase has to be all that it heard:
	 * inside a longer sentence it is someone talking, not someone calling the panel.
	 */
	fun confidence(result: String): Double? =
		runCatching {
			val json = Json.parseToJsonElement(result).jsonObject
			if (json["text"]?.jsonPrimitive?.content != PHRASE) return null
			json
				.getValue("result")
				.jsonArray
				.minOf {
					it.jsonObject
						.getValue("conf")
						.jsonPrimitive.double
				}
		}.getOrNull()

	fun isEnough(confidence: Double?): Boolean = confidence != null && confidence >= MIN_CONFIDENCE
}
