package dev.xoa.marc2d

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** Against a real WebSocket: both bugs found so far in this client only appeared with one. */
class HaClientTest {
	private var server: EmbeddedServer<*, *>? = null

	@AfterTest
	fun stop() {
		server?.stop(0, 0)
	}

	private suspend fun fakeHa(session: suspend DefaultWebSocketServerSession.(Int) -> Unit): HaConnection {
		val connections = AtomicInteger()
		val started =
			embeddedServer(CIO, port = 0) {
				install(WebSockets)
				routing {
					webSocket("/api/websocket") {
						send(Frame.Text("""{"type":"auth_required"}"""))
						incoming.receive()
						send(Frame.Text("""{"type":"auth_ok"}"""))
						session(connections.incrementAndGet())
					}
				}
			}.start(wait = false)
		server = started
		val port =
			started.engine
				.resolvedConnectors()
				.first()
				.port
		return HaConnection("http://127.0.0.1:$port", "token", "Panel")
	}

	private suspend fun DefaultWebSocketServerSession.subscribeId(): Int {
		val text = (incoming.receive() as Frame.Text).readText()
		return HaProtocol.parse(text)["id"]!!.jsonPrimitive.int
	}

	// The panel reports its state as soon as it connects, so other requests can come first.
	private suspend fun DefaultWebSocketServerSession.receiveRequest(type: String): String {
		while (true) {
			val text = (incoming.receive() as Frame.Text).readText()
			if (HaProtocol.parse(text)["type"]?.jsonPrimitive?.content == type) return text
		}
	}

	@Test
	fun `applies the configuration as soon as it arrives`() =
		runBlocking {
			val connection =
				fakeHa {
					val id = subscribeId()
					send(Frame.Text("""{"id":$id,"type":"result","success":true}"""))
					send(Frame.Text("""{"id":$id,"type":"event","event":{"kind":"config","config":{"sensors":false}}}"""))
					awaitCancellation()
				}
			val config = CompletableDeferred<PanelConfig>()
			val job =
				launch {
					HaClient().run(connection, PanelLink(), {}, { config.complete(it) }, {}, {})
				}
			assertEquals(false, withTimeout(5.seconds) { config.await() }.sensors)
			job.cancel()
		}

	@Test
	fun `reads the configured entities in one request and delivers readings and charts`() =
		runBlocking {
			val asked = CompletableDeferred<String>()
			val connection =
				fakeHa {
					val id = subscribeId()
					send(Frame.Text("""{"id":$id,"type":"result","success":true}"""))
					send(
						Frame.Text(
							"""{"id":$id,"type":"event","event":{"kind":"config","config":{
							"readings":[{"entity":"sensor.car_battery","name":"Car"}],
							"charts":[{"entity":"sensor.price","name":"Electricity"}]}}}""",
						),
					)
					val request = receiveRequest("subscribe_entities")
					asked.complete(request)
					val stateId = HaProtocol.parse(request)["id"]!!.jsonPrimitive.int
					send(Frame.Text("""{"id":$stateId,"type":"result","success":true}"""))
					send(
						Frame.Text(
							"""{"id":$stateId,"type":"event","event":{"a":{
							"sensor.car_battery":{"s":"73.0","a":{"unit_of_measurement":"%"}},
							"sensor.price":{"s":"0.19","a":{"raw_today":[{"start":"2026-11-15T23:00:00+01:00","value":0.19}]}}}}}""",
						),
					)
					awaitCancellation()
				}
			val shown = CompletableDeferred<Pair<List<Reading>, List<ChartData>>>()
			val job =
				launch {
					HaClient().run(connection, PanelLink(), {}, {}, {}, {}, onDisplay = { readings, charts ->
						if (readings.isNotEmpty()) shown.complete(readings to charts)
					})
				}
			val request = HaProtocol.parse(withTimeout(5.seconds) { asked.await() })
			assertEquals("subscribe_entities", request["type"]!!.jsonPrimitive.content)
			assertEquals("[\"sensor.car_battery\",\"sensor.price\"]", request["entity_ids"].toString())
			val (readings, charts) = withTimeout(5.seconds) { shown.await() }
			assertEquals(listOf(Reading("Car", "73", "%", warning = false, level = 0.73f)), readings)
			assertEquals("Electricity", charts.single().name)
			job.cancel()
		}

	@Test
	fun `reads the weather in the same request as the readings`() =
		runBlocking {
			val asked = CompletableDeferred<String>()
			val connection =
				fakeHa {
					val id = subscribeId()
					send(Frame.Text("""{"id":$id,"type":"result","success":true}"""))
					send(
						Frame.Text(
							"""{"id":$id,"type":"event","event":{"kind":"config","config":{
							"weather":"weather.home","readings":[{"entity":"sensor.outside","name":"Out"}]}}}""",
						),
					)
					val request = receiveRequest("subscribe_entities")
					asked.complete(request)
					val stateId = HaProtocol.parse(request)["id"]!!.jsonPrimitive.int
					send(Frame.Text("""{"id":$stateId,"type":"result","success":true}"""))
					send(
						Frame.Text(
							"""{"id":$stateId,"type":"event","event":{"a":{
							"weather.home":{"s":"sunny","a":{"temperature":21}},"sensor.outside":{"s":"18","a":{}}}}}""",
						),
					)
					val forecast = HaProtocol.parse(receiveRequest("call_service"))["id"]!!.jsonPrimitive.int
					send(Frame.Text("""{"id":$forecast,"type":"result","success":true,"result":{"response":{}}}"""))
					awaitCancellation()
				}
			val weather = CompletableDeferred<WeatherReport?>()
			val job = launch { HaClient().run(connection, PanelLink(), {}, {}, { weather.complete(it) }, {}) }
			val request = HaProtocol.parse(withTimeout(5.seconds) { asked.await() })
			assertEquals("[\"weather.home\",\"sensor.outside\"]", request["entity_ids"].toString())
			val report = withTimeout(5.seconds) { weather.await() }
			assertEquals("sunny", report?.condition)
			assertEquals(21.0, report?.temperature)
			job.cancel()
		}

	@Test
	fun `a refusal ends the connection, so that retrying opens a new one`() =
		runBlocking {
			val connection =
				fakeHa { number ->
					val id = subscribeId()
					if (number == 1) {
						send(Frame.Text("""{"id":$id,"type":"result","success":false,"error":{"code":"unknown_command"}}"""))
						awaitCancellation()
					}
					send(Frame.Text("""{"id":$id,"type":"result","success":true}"""))
					send(Frame.Text("""{"id":$id,"type":"event","event":{"kind":"config","config":{}}}"""))
					awaitCancellation()
				}
			val statuses = mutableListOf<HaStatus>()
			val client = HaClient()
			withTimeout(5.seconds) { client.run(connection, PanelLink(), { statuses += it }, {}, {}, {}) }
			assertEquals(listOf<HaStatus>(HaStatus.NotAdded), statuses)

			val config = CompletableDeferred<PanelConfig>()
			val job = launch { client.run(connection, PanelLink(), { statuses += it }, { config.complete(it) }, {}, {}) }
			withTimeout(5.seconds) { config.await() }
			job.cancel()
		}

	@Test
	fun `delivers commands and reports its values`() =
		runBlocking {
			val reported = CompletableDeferred<String>()
			val connection =
				fakeHa {
					val id = subscribeId()
					send(Frame.Text("""{"id":$id,"type":"result","success":true}"""))
					send(Frame.Text("""{"id":$id,"type":"event","event":{"kind":"siren","on":true}}"""))
					reported.complete((incoming.receive() as Frame.Text).readText())
					awaitCancellation()
				}
			val command = CompletableDeferred<HaProtocol.PanelEvent>()
			val link = PanelLink()
			link.report("battery", JsonPrimitive(80))
			val job = launch { HaClient().run(connection, link, {}, {}, {}, { command.complete(it) }) }

			assertEquals(HaProtocol.PanelEvent.Siren(true), withTimeout(5.seconds) { command.await() })
			assertTrue(""""battery":80""" in withTimeout(5.seconds) { reported.await() })
			job.cancel()
		}

	@Test
	fun `stops listening when Home Assistant refuses a voice request`() =
		runBlocking {
			val connection =
				fakeHa {
					val id = subscribeId()
					send(Frame.Text("""{"id":$id,"type":"result","success":true}"""))
					var run = -1
					while (run < 0) {
						val text = (incoming.receive() as Frame.Text).readText()
						if ("assist_pipeline/run" in text) run = HaProtocol.parse(text)["id"]!!.jsonPrimitive.int
					}
					send(Frame.Text("""{"id":$run,"type":"result","success":false,"error":{"code":"pipeline-not-found"}}"""))
					awaitCancellation()
				}
			val link = PanelLink()
			val stopped = CompletableDeferred<Unit>()
			val job = launch { HaClient().run(connection, link, {}, {}, {}, {}) }
			withTimeout(5.seconds) {
				link.assistListeners.first { it > 0 }
				link.requestAssist(AssistRun(null, Channel(Channel.UNLIMITED)) { stopped.complete(Unit) })
				stopped.await()
			}
			job.cancel()
		}

	@Test
	fun `streams a voice request and plays the reply`() =
		runBlocking {
			val received = CompletableDeferred<List<Int>>()
			val connection =
				fakeHa {
					val id = subscribeId()
					send(Frame.Text("""{"id":$id,"type":"result","success":true}"""))
					var run = -1
					while (run < 0) {
						val text = (incoming.receive() as Frame.Text).readText()
						if ("assist_pipeline/run" in text) run = HaProtocol.parse(text)["id"]!!.jsonPrimitive.int
					}
					send(Frame.Text("""{"id":$run,"type":"result","success":true}"""))
					send(Frame.Text("""{"id":$run,"type":"event","event":{"type":"run-start","data":{"runner_data":{"stt_binary_handler_id":7}}}}"""))
					val first = incoming.receive() as Frame.Binary
					val last = incoming.receive() as Frame.Binary
					received.complete(listOf(first.data.size, first.data[0].toInt(), last.data.size))
					send(Frame.Text("""{"id":$run,"type":"event","event":{"type":"tts-end","data":{"tts_output":{"url":"/api/a.mp3"}}}}"""))
					awaitCancellation()
				}
			val link = PanelLink()
			val reply = CompletableDeferred<HaProtocol.PanelEvent>()
			val job = launch { HaClient().run(connection, link, {}, {}, {}, { reply.complete(it) }) }
			val audio = Channel<ByteArray>(Channel.UNLIMITED)
			audio.trySend(byteArrayOf(1, 2))
			audio.close()
			withTimeout(5.seconds) {
				link.assistListeners.first { it > 0 }
				link.requestAssist(AssistRun(null, audio) {})
				assertEquals(listOf(3, 7, 1), received.await())
				assertEquals(HaProtocol.PanelEvent.Play("/api/a.mp3"), reply.await())
			}
			job.cancel()
		}
}
