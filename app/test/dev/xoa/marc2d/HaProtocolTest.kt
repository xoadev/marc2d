package dev.xoa.marc2d

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
