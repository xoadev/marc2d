package dev.xoa.marc2d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class HaProtocolTest {
	@Test
	fun `converts the Home Assistant URL into the WebSocket one`() {
		assertEquals("ws://ha.local:8123/api/websocket", HaProtocol.webSocketUrl("http://ha.local:8123/"))
		assertEquals("wss://ha.example/api/websocket", HaProtocol.webSocketUrl(" https://ha.example "))
		assertEquals("ws://192.168.1.2:8123/api/websocket", HaProtocol.webSocketUrl("192.168.1.2:8123"))
	}

	@Test
	fun `reads the configuration the integration pushes`() {
		val message =
			HaProtocol.parse(
				"""
				{"id":1,"type":"event","event":{
				  "intercoms":[{"name":"Parking","server":"192.0.2.8","extension":"9901#1","password":"s3"}],
				  "future":true
				}}
				""".trimIndent(),
			)

		val result = assertIs<HaProtocol.ConfigResult.Ok>(HaProtocol.configEvent(message))

		assertEquals(
			"Parking",
			result.config.intercoms
				.single()
				.name,
		)
	}

	@Test
	fun `what is turned off in Home Assistant does not arrive`() {
		val message = HaProtocol.parse("""{"id":1,"type":"event","event":{"intercoms":[],"cameras":[]}}""")

		val result = assertIs<HaProtocol.ConfigResult.Ok>(HaProtocol.configEvent(message))

		assertEquals(PanelConfig(), result.config)
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
		val message = HaProtocol.parse("""{"id":1,"type":"event","event":{"intercoms":[{"password":"s3"}]}}""")

		val result = assertIs<HaProtocol.ConfigResult.Invalid>(HaProtocol.configEvent(message))

		assertFalse("s3" in result.reason)
	}
}
