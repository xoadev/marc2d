package dev.xoa.marc2d

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HaProtocolTest {
	@Test
	fun `converts the Home Assistant URL into the WebSocket one`() {
		assertEquals("ws://ha.local:8123/api/websocket", HaProtocol.webSocketUrl("http://ha.local:8123/"))
		assertEquals("wss://ha.example/api/websocket", HaProtocol.webSocketUrl(" https://ha.example "))
		assertEquals("ws://192.168.1.2:8123/api/websocket", HaProtocol.webSocketUrl("192.168.1.2:8123"))
	}

	private fun event(body: String) = HaProtocol.parse("""{"id":1,"type":"event","event":$body}""")

	@Test
	fun `reads the configuration the integration pushes`() {
		val message =
			event(
				"""
				{"kind":"config","config":{
				  "intercoms":[{"name":"Parking","server":"192.0.2.8","extension":"9901#1","password":"s3"}],
				  "sensors":false,
				  "future":true
				}}
				""".trimIndent(),
			)

		val config = assertIs<HaProtocol.PanelEvent.Config>(HaProtocol.panelEvent(message))
		val result = assertIs<HaProtocol.ConfigResult.Ok>(config.result)

		assertEquals(
			"Parking",
			result.config.intercoms
				.single()
				.name,
		)
		assertFalse(result.config.sensors)
	}

	@Test
	fun `reads the commands`() {
		assertEquals(HaProtocol.PanelEvent.Siren(true), HaProtocol.panelEvent(event("""{"kind":"siren","on":true}""")))
		assertEquals(HaProtocol.PanelEvent.Mood("Alarm"), HaProtocol.panelEvent(event("""{"kind":"mood","value":"Alarm"}""")))
		assertEquals(
			HaProtocol.PanelEvent.Doorbell("Parking", "RING"),
			HaProtocol.panelEvent(event("""{"kind":"doorbell","intercom":"Parking","payload":"RING"}""")),
		)
	}

	@Test
	fun `reads the audio commands`() {
		assertEquals(HaProtocol.PanelEvent.Play("http://x/a.mp3"), HaProtocol.panelEvent(event("""{"kind":"play","url":"http://x/a.mp3"}""")))
		assertEquals(HaProtocol.PanelEvent.Stop, HaProtocol.panelEvent(event("""{"kind":"stop"}""")))
		assertEquals(HaProtocol.PanelEvent.Volume(0.5), HaProtocol.panelEvent(event("""{"kind":"volume","level":0.5}""")))
	}

	@Test
	fun `sends HA's media through the address the panel knows`() {
		val base = "http://192.0.2.20:8123"
		assertEquals("$base/api/tts_proxy/a.mp3?x=1", HaProtocol.mediaUrl("/api/tts_proxy/a.mp3?x=1", "$base/"))
		assertEquals("https://example.com/a.mp3", HaProtocol.mediaUrl("https://example.com/a.mp3", base))
	}

	@Test
	fun `does not send another server's media to HA`() {
		val other = "https://example.com/api/a.mp3"
		assertEquals(other, HaProtocol.mediaUrl(other, "http://192.0.2.20:8123"))
	}

	@Test
	fun `a refused voice request ends it`() {
		assertEquals(HaProtocol.AssistEvent.Ended, HaProtocol.assistEvent(HaProtocol.parse("""{"id":1,"type":"result","success":false}""")))
		assertNull(HaProtocol.assistEvent(HaProtocol.parse("""{"id":1,"type":"result","success":true}""")))
	}

	@Test
	fun `reads an update, which needs its url and its hash`() {
		assertEquals(
			HaProtocol.PanelEvent.Update("https://example.com/a.apk", "abc"),
			HaProtocol.panelEvent(event("""{"kind":"update","url":"https://example.com/a.apk","sha256":"abc"}""")),
		)
		assertEquals(null, HaProtocol.panelEvent(event("""{"kind":"update","url":"https://example.com/a.apk"}""")))
	}

	@Test
	fun `reads the brightness`() {
		assertEquals(HaProtocol.PanelEvent.Brightness(40.0), HaProtocol.panelEvent(event("""{"kind":"brightness","level":40}""")))
	}

	@Test
	fun `follows a voice request`() {
		fun assist(body: String) = HaProtocol.assistEvent(event(body))

		assertEquals(
			HaProtocol.AssistEvent.Started(7),
			assist("""{"type":"run-start","data":{"runner_data":{"stt_binary_handler_id":7}}}"""),
		)
		assertEquals(HaProtocol.AssistEvent.StopListening, assist("""{"type":"stt-vad-end","data":{}}"""))
		assertEquals(HaProtocol.AssistEvent.Reply("/api/a.mp3"), assist("""{"type":"tts-end","data":{"tts_output":{"url":"/api/a.mp3"}}}"""))
		assertEquals(HaProtocol.AssistEvent.Ended, assist("""{"type":"error","data":{}}"""))
		assertEquals(HaProtocol.AssistEvent.Ended, assist("""{"type":"run-end"}"""))
		assertNull(assist("""{"type":"intent-start"}"""))
	}

	@Test
	fun `recognizes the wake phrase`() {
		val wake = WakeWord("jarvis")
		val sure = """{"result":[{"conf":0.95,"word":"jarvis"}],"text":"jarvis"}"""
		assertEquals(0.95, wake.confidence(sure))
		assertTrue(WakeWord.isEnough(wake.confidence(sure)))
		assertFalse(WakeWord.isEnough(wake.confidence("""{"result":[{"conf":0.79,"word":"jarvis"}],"text":"jarvis"}""")))
		assertFalse(WakeWord.isEnough(null))
		assertEquals(null, wake.confidence("""{"result":[{"conf":1.0,"word":"hola"}],"text":"hola"}"""))
		assertEquals(null, wake.confidence("""{"text":"[unk]"}"""))
		assertEquals(null, wake.confidence("""{"text":"jarvis"}"""))
		assertEquals(null, wake.confidence("""{"result":[],"text":"jarvis ya voy"}"""))
		assertEquals(null, wake.confidence("not json"))
	}

	@Test
	fun `refuses a model archive that escapes its folder`() {
		val bytes = java.io.ByteArrayOutputStream()
		java.util.zip.ZipOutputStream(bytes).use {
			it.putNextEntry(java.util.zip.ZipEntry("../evil"))
			it.write(1)
			it.closeEntry()
		}
		val folder =
			java.nio.file.Files
				.createTempDirectory("model")
				.toFile()
		assertTrue(runCatching { VoiceModel.unzip(bytes.toByteArray().inputStream(), folder) }.isFailure)
		assertFalse(java.io.File(folder.parentFile, "evil").exists())
	}

	@Test
	fun `ignores what it does not know`() {
		assertNull(HaProtocol.panelEvent(event("""{"kind":"future"}""")))
		assertNull(HaProtocol.panelEvent(event("""{"kind":"siren"}""")))
	}

	@Test
	fun `reports its values`() {
		val message = HaProtocol.panelState(3, mapOf("battery" to JsonPrimitive(80), "charging" to JsonPrimitive(true)))
		assertEquals("""{"id":3,"type":"marc2d/state","values":{"battery":80,"charging":true}}""", message)
	}

	@Test
	fun `an unavailable entity gives no report`() {
		val state = HaProtocol.parse("""{"event":{"a":{"weather.home":{"s":"unavailable","a":{}}}}}""")
		assertNull(HaProtocol.weather("weather.home", state, HaProtocol.parse("{}"), Instant.EPOCH))
	}

	@Test
	fun `tells apart a panel that is not added from another error`() {
		fun refusal(code: String) = HaProtocol.parse("""{"id":1,"type":"result","success":false,"error":{"code":"$code"}}""")

		assertEquals(HaProtocol.ConfigResult.NotAdded, HaProtocol.subscribeError(refusal("not_found")))
		assertEquals(HaProtocol.ConfigResult.NotAdded, HaProtocol.subscribeError(refusal("unknown_command")))
		assertIs<HaProtocol.ConfigResult.Invalid>(HaProtocol.subscribeError(refusal("unauthorized")))
	}

	@Test
	fun `a malformed configuration does not reveal its content`() {
		val message = event("""{"kind":"config","config":{"intercoms":[{"password":"s3"}]}}""")

		val config = assertIs<HaProtocol.PanelEvent.Config>(HaProtocol.panelEvent(message))
		val result = assertIs<HaProtocol.ConfigResult.Invalid>(config.result)

		assertFalse("s3" in result.reason)
	}

	@Test
	fun `knows when the phrase ended`() {
		val wake = WakeWord("jarvis")
		assertEquals(1.47, wake.end("""{"result":[{"conf":1.0,"word":"jarvis","start":0.6,"end":1.47}],"text":"jarvis"}"""))
		assertEquals(null, wake.end("""{"result":[{"conf":1.0,"word":"hola","end":1.0}],"text":"hola"}"""))
		assertEquals(null, wake.end("""{"result":[{"conf":1.0,"word":"jarvis"}],"text":"jarvis"}"""))
	}

	@Test
	fun `the phrase can have several words, and the least clear one counts`() {
		val wake = WakeWord("oye mark")
		assertEquals(0.5, wake.confidence("""{"result":[{"conf":1.0,"word":"oye"},{"conf":0.5,"word":"mark"}],"text":"oye mark"}"""))
		assertEquals(null, wake.confidence("""{"result":[{"conf":1.0,"word":"jarvis"}],"text":"jarvis"}"""))
	}

	@Test
	fun `whatever the form has becomes a phrase Vosk can hear`() {
		assertEquals("oye mark", WakeWord("  Oye,   Mark! ").phrase)
		assertEquals("oye maría", WakeWord("OYE MARÍA").phrase)
		assertEquals(VoiceConfig.DEFAULT_PHRASE, WakeWord("  ?! ").phrase)
	}

	@Test
	fun `the grammar gives other speech somewhere to go`() {
		val entries = Json.parseToJsonElement(WakeWord("jarvis").grammar).jsonArray.map { it.jsonPrimitive.content }
		assertEquals("jarvis", entries.first())
		assertTrue("[unk]" in entries)
		assertTrue("oye maría" in entries)
		assertEquals(entries.size, entries.distinct().size)
	}

	@Test
	fun `a phrase that is also a decoy is only there once`() {
		val entries = Json.parseToJsonElement(WakeWord("hola").grammar).jsonArray.map { it.jsonPrimitive.content }
		assertEquals(1, entries.count { it == "hola" })
		assertEquals("hola", entries.first())
	}
}

class AssistTimerTest {
	private fun step(
		type: String,
		code: String? = null,
	) = HaProtocol.AssistStep(type, code)

	@Test
	fun `each step says how long it took, and the total`() {
		val timer = AssistTimer()
		assertNull(timer.line(step("run-start"), 1000))
		assertEquals("Voice: voice ended in 3.2 s, 3.2 s so far", timer.line(step("stt-vad-end"), 4200))
		assertEquals("Voice: transcribed in 8.0 s, 11.2 s so far", timer.line(step("stt-end"), 12200))
		assertEquals("Voice: reply ready in 0.5 s, 11.7 s so far", timer.line(step("tts-end"), 12700))
	}

	@Test
	fun `steps that say nothing about the time are left out`() {
		val timer = AssistTimer()
		timer.line(step("run-start"), 0)
		assertNull(timer.line(step("stt-start"), 100))
		assertEquals("Voice: understood in 0.2 s, 0.2 s so far", timer.line(step("intent-end"), 200))
	}

	@Test
	fun `a failure gives its code`() {
		val timer = AssistTimer()
		timer.line(step("run-start"), 0)
		assertEquals("Voice: failed in 1.0 s, 1.0 s so far (stt-no-text-recognized)", timer.line(step("error", "stt-no-text-recognized"), 1000))
	}

	@Test
	fun `nothing is measured outside a request`() {
		val timer = AssistTimer()
		assertNull(timer.line(step("stt-end"), 1000))
		timer.line(step("run-start"), 0)
		timer.line(step("run-end"), 100)
		assertNull(timer.line(step("stt-end"), 200))
	}

	@Test
	fun `only the step and its code are read, never what was said`() {
		val message =
			Json
				.parseToJsonElement(
					"""{"event":{"type":"stt-end","data":{"stt_output":{"text":"abre la puerta"}}}}""",
				).jsonObject
		assertEquals(HaProtocol.AssistStep("stt-end"), HaProtocol.assistStep(message))
		val failed =
			Json
				.parseToJsonElement(
					"""{"event":{"type":"error","data":{"code":"stt-stream-failed","message":"abre la puerta"}}}""",
				).jsonObject
		assertEquals(HaProtocol.AssistStep("error", "stt-stream-failed"), HaProtocol.assistStep(failed))
	}
}

class AudioHistoryTest {
	private fun bytes(range: IntRange) = ByteArray(range.count()) { (range.first + it).toByte() }

	/** Measured with the panel's model: "jarvis" ended at 1.47 s and was confirmed at 2.4 s, after "qué" had started. */
	@Test
	fun `gives back what came after a moment`() {
		val history = AudioHistory(100)
		history.add(bytes(0..39), 40)
		history.add(bytes(40..59), 20)
		assertEquals(60, history.total)
		assertContentEquals(bytes(50..59), history.since(50))
		assertContentEquals(ByteArray(0), history.since(60))
	}

	@Test
	fun `only as much as it keeps, and always whole samples`() {
		val history = AudioHistory(10)
		history.add(bytes(0..24), 25)
		assertContentEquals(bytes(16..24), history.since(0))
		assertContentEquals(bytes(18..24), history.since(17))
	}
}

class AmplifyTest {
	@Test
	fun `raises the voice, in little-endian`() {
		// -13 and 100, as the microphone gives them.
		val quiet = byteArrayOf(0xF3.toByte(), 0xFF.toByte(), 100, 0)
		assertContentEquals(byteArrayOf(0xCC.toByte(), 0xFF.toByte(), 0x90.toByte(), 0x01), amplify(quiet, 4))
	}

	@Test
	fun `a loud sound stays at the top instead of wrapping round`() {
		val loud = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte())
		assertContentEquals(byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()), amplify(loud, 4))
	}
}
