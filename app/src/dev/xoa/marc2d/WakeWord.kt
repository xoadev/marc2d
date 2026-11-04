package dev.xoa.marc2d

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What Vosk may hear. Words missing from the model's Spanish vocabulary are ignored by it, hence the variants. */
object WakeWord {
	const val GRAMMAR = """["oye mark", "oye marc", "oye marco", "oye mar", "[unk]"]"""

	fun heard(result: String): Boolean {
		val text =
			runCatching {
				Json
					.parseToJsonElement(result)
					.jsonObject["text"]
					?.jsonPrimitive
					?.content
			}.getOrNull()
				.orEmpty()
		return text.startsWith("oye mar")
	}
}
