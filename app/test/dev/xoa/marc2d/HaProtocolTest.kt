package dev.xoa.marc2d

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

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
		assertTrue(WakeWord.heard("""{"text":"oye mark"}"""))
		assertTrue(WakeWord.heard("""{"text":"oye marco"}"""))
		assertFalse(WakeWord.heard("""{"text":"[unk]"}"""))
		assertFalse(WakeWord.heard("""{"text":"mark"}"""))
		assertFalse(WakeWord.heard("not json"))
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
}
