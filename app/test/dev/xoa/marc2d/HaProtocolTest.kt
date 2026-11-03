package dev.xoa.marc2d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class HaProtocolTest {
	@Test
	fun `convierte la URL de Home Assistant en la del WebSocket`() {
		assertEquals("ws://ha.local:8123/api/websocket", HaProtocol.webSocketUrl("http://ha.local:8123/"))
		assertEquals("wss://ha.example/api/websocket", HaProtocol.webSocketUrl(" https://ha.example "))
		assertEquals("ws://192.168.1.2:8123/api/websocket", HaProtocol.webSocketUrl("192.168.1.2:8123"))
	}

	@Test
	fun `lee la configuracion que devuelve el script`() {
		val message =
			HaProtocol.parse(
				"""
				{"id":1,"type":"result","success":true,"result":{"context":{"id":"x"},"response":{
				  "intercoms":[{"name":"Parking","server":"192.0.2.8","extension":"9901#1","password":"s3"}],
				  "futuro":true
				}}}
				""".trimIndent(),
			)

		val result = assertIs<HaProtocol.ConfigResult.Ok>(HaProtocol.configResult(message))

		assertEquals(
			"Parking",
			result.config.intercoms
				.single()
				.name,
		)
	}

	@Test
	fun `detecta que falta el script`() {
		val message =
			HaProtocol.parse("""{"id":1,"type":"result","success":false,"error":{"code":"not_found","message":"x"}}""")

		assertEquals(HaProtocol.ConfigResult.MissingScript, HaProtocol.configResult(message))
	}

	@Test
	fun `una respuesta sin formato no revela su contenido`() {
		val message =
			HaProtocol.parse(
				"""{"id":1,"type":"result","success":true,"result":{"response":{"intercoms":[{"password":"s3"}]}}}""",
			)

		val result = assertIs<HaProtocol.ConfigResult.Invalid>(HaProtocol.configResult(message))

		assertFalse("s3" in result.reason)
	}
}
