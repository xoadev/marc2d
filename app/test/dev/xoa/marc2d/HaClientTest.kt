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
				link.assist.subscriptionCount.first { it > 0 }
				link.requestAssist(AssistRun(null, audio) {})
				assertEquals(listOf(3, 7, 1), received.await())
				assertEquals(HaProtocol.PanelEvent.Play("/api/a.mp3"), reply.await())
			}
			job.cancel()
		}
}
