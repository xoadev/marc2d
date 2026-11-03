package dev.xoa.marc2d

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the QR code shown by Home Assistant carries: never the token itself. */
data class EnrollmentInvite(
	val url: String,
	val code: String,
) {
	override fun toString(): String = "EnrollmentInvite(url=$url)"

	companion object {
		/** Returns nothing for any other QR code that the camera happens to see. */
		fun parse(text: String): EnrollmentInvite? {
			val uri = runCatching { URI(text) }.getOrNull() ?: return null
			if (uri.scheme != "marc2d" || uri.host != "enroll") return null
			val query =
				uri.rawQuery
					.orEmpty()
					.split("&")
					.associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("=", ""), Charsets.UTF_8) }
			val url = query["url"]?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
			val code = query["code"]?.takeIf { it.isNotBlank() } ?: return null
			return EnrollmentInvite(url, code)
		}
	}
}

class EnrollmentRejectedException : Exception("Home Assistant rejected the code")

/** Exchanges the one-time code for the panel's own token. */
class EnrollmentClient {
	private val client = HttpClient(OkHttp)

	suspend fun enroll(invite: EnrollmentInvite): HaConnection {
		val response =
			client.post("${invite.url.trimEnd('/')}/api/marc2d/enroll") {
				contentType(ContentType.Application.Json)
				setBody(JsonObject(mapOf("code" to JsonPrimitive(invite.code))).toString())
			}
		if (response.status != HttpStatusCode.OK) throw EnrollmentRejectedException()
		val body = LENIENT.decodeFromString<EnrollmentResponse>(response.bodyAsText())
		return HaConnection(invite.url.trimEnd('/'), body.token, body.panel)
	}

	private companion object {
		val LENIENT = Json { ignoreUnknownKeys = true }
	}
}

@Serializable
private data class EnrollmentResponse(
	val token: String,
	val panel: String,
)
