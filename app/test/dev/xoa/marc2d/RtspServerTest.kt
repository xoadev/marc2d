package dev.xoa.marc2d

import java.io.File
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Against real sockets, and against the real ffmpeg when the machine has it: it is what Home Assistant and Frigate use. */
class RtspServerTest {
	private class FakeSource(
		private val frames: List<H264Frame> = listOf(KEY_FRAME),
		private val failing: Boolean = false,
	) : H264Source {
		@Volatile var starts = 0

		@Volatile var stops = 0

		@Volatile var keyFrameRequests = 0

		@Volatile private var feeding: kotlinx.coroutines.Job? = null

		@Volatile var fail: () -> Unit = {}

		override suspend fun start(
			onFrame: (H264Frame) -> Unit,
			onFailure: () -> Unit,
		): H264Parameters {
			check(!failing) { "camera in use" }
			starts++
			fail = onFailure
			feeding =
				CoroutineScope(Dispatchers.IO).launch {
					var index = 0
					var time = 0L
					while (isActive) {
						val frame = frames[index % frames.size]
						onFrame(H264Frame(frame.data, time, frame.keyFrame))
						index++
						time += 40_000
						delay(10.milliseconds)
					}
				}
			return PARAMETERS
		}

		override fun requestKeyFrame() {
			keyFrameRequests++
		}

		override fun stop() {
			stops++
			feeding?.cancel()
		}
	}

	private fun withServer(
		source: H264Source,
		onCamera: (Boolean) -> Unit = {},
		body: suspend (Int) -> Unit,
	) = runBlocking {
		val port = CompletableDeferred<Int>()
		val server = RtspServer(PASSWORD, source, stopDelay = 100.milliseconds, onCamera = onCamera)
		val job = launch(Dispatchers.IO) { server.run(0, port::complete) }
		try {
			withTimeout(30.seconds) { body(port.await()) }
		} finally {
			job.cancelAndJoin()
		}
	}

	/** A plain RTSP client, to look at what is on the wire. */
	private class RtspClient(
		port: Int,
	) : AutoCloseable {
		// withTimeout cannot stop a blocked read: without this, a bug here would hang the tests instead of failing them.
		private val socket = Socket("127.0.0.1", port).apply { soTimeout = 10_000 }
		private val input = socket.getInputStream().buffered()
		private var cseq = 0

		fun request(
			method: String,
			uri: String = URI,
			headers: List<String> = emptyList(),
			password: String? = PASSWORD,
			nonce: String? = null,
		): Response {
			val auth =
				if (nonce != null && password != null) {
					val ha1 = md5("marc2d:marc2d:$password")
					val response = md5("$ha1:$nonce:${md5("$method:$uri")}")
					listOf("""Authorization: Digest username="marc2d", realm="marc2d", nonce="$nonce", uri="$uri", response="$response"""")
				} else {
					emptyList()
				}
			val text = (listOf("$method $uri RTSP/1.0", "CSeq: ${++cseq}") + headers + auth + "" + "").joinToString("\r\n")
			socket.getOutputStream().write(text.toByteArray())
			return read()
		}

		fun read(): Response {
			val lines = mutableListOf<String>()
			val line = StringBuilder()
			while (true) {
				val byte = input.read()
				check(byte >= 0) { "connection closed" }
				if (byte == '\n'.code) {
					if (line.isEmpty()) break
					lines += line.toString()
					line.clear()
				} else if (byte != '\r'.code) {
					line.append(byte.toChar())
				}
			}
			val headers = lines.drop(1).associate { it.substringBefore(":").lowercase() to it.substringAfter(":").trim() }
			val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
			input.readNBytes(body, 0, body.size)
			return Response(lines[0].split(" ")[1].toInt(), headers, String(body))
		}

		/** The nonce to answer with, after being rejected. */
		fun challenge(): String {
			val response = request("DESCRIBE")
			assertEquals(401, response.status)
			return Regex("nonce=\"([^\"]+)\"").find(response.headers.getValue("www-authenticate"))!!.groupValues[1]
		}

		/** The next RTP packet that arrives over the connection. */
		fun readPacket(): ByteArray {
			check(input.read() == '$'.code)
			input.read()
			val size = (input.read() shl 8) or input.read()
			return input.readNBytes(size)
		}

		override fun close() {
			socket.close()
		}
	}

	private class Response(
		val status: Int,
		val headers: Map<String, String>,
		val body: String,
	)

	@Test
	fun `nothing turns the camera on before authenticating`() {
		val source = FakeSource()
		withServer(source) { port ->
			RtspClient(port).use { client ->
				assertEquals(200, client.request("OPTIONS").status)
				val nonce = client.challenge()
				assertEquals(401, client.request("DESCRIBE", password = "wrong", nonce = nonce).status)
				assertEquals(401, client.request("SETUP", headers = listOf(TCP)).status)
				assertEquals(401, client.request("PLAY").status)
			}
			delay(200.milliseconds)
			assertEquals(0, source.starts)
		}
	}

	@Test
	fun `the camera starts when asked and stops after the last client`() {
		val source = FakeSource()
		withServer(source) { port ->
			RtspClient(port).use { client ->
				val nonce = client.challenge()
				val described = client.request("DESCRIBE", nonce = nonce)
				assertEquals(200, described.status)
				assertTrue("sprop-parameter-sets=Z0IAHg==,aM4=" in described.body, described.body)
				assertTrue("profile-level-id=42001e" in described.body, described.body)
				assertEquals(1, source.starts)

				val setup = client.request("SETUP", "$URI/track0", listOf(TCP), nonce = nonce)
				assertEquals(200, setup.status)
				assertTrue("interleaved=0-1" in setup.headers.getValue("transport"))
				assertEquals(200, client.request("PLAY", nonce = nonce).status)
				assertTrue(source.keyFrameRequests > 0)

				val packet = client.readPacket()
				assertEquals(0x80, packet[0].toInt() and 0xFF)
				assertEquals(96, packet[1].toInt() and 0x7F)
			}
			waitFor { source.stops == 1 }
			assertEquals(1, source.starts)
		}
	}

	@Test
	fun `it says when the camera is on, for as long as it really is`() {
		val changes = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
		val source = FakeSource()
		withServer(source, onCamera = { changes += it }) { port ->
			RtspClient(port).use { client ->
				assertEquals(emptyList(), changes.toList())
				assertEquals(200, client.request("DESCRIBE", nonce = client.challenge()).status)
				assertEquals(listOf(true), changes.toList())
			}
			waitFor { source.stops == 1 }
			waitFor { changes.size == 2 }
			assertEquals(listOf(true, false), changes.toList())
		}
	}

	@Test
	fun `a camera that is lost stops saying it is on`() {
		val changes = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
		val source = FakeSource()
		withServer(source, onCamera = { changes += it }) { port ->
			RtspClient(port).use { client ->
				client.request("DESCRIBE", nonce = client.challenge())
				source.fail()
				waitFor { changes.size == 2 }
			}
			assertEquals(listOf(true, false), changes.toList())
		}
	}

	@Test
	fun `a client that comes back in time does not restart the camera`() {
		val source = FakeSource()
		withServer(source) { port ->
			repeat(2) {
				RtspClient(port).use { client ->
					assertEquals(200, client.request("DESCRIBE", nonce = client.challenge()).status)
				}
			}
			assertEquals(1, source.starts)
			waitFor { source.stops == 1 }
		}
	}

	@Test
	fun `clients share the camera, which waits for all of them`() {
		val source = FakeSource()
		withServer(source) { port ->
			val first = RtspClient(port)
			RtspClient(port).use { second ->
				assertEquals(200, first.request("DESCRIBE", nonce = first.challenge()).status)
				assertEquals(200, second.request("DESCRIBE", nonce = second.challenge()).status)
			}
			delay(300.milliseconds)
			assertEquals(1, source.starts)
			assertEquals(0, source.stops)
			first.close()
			waitFor { source.stops == 1 }
		}
	}

	@Test
	fun `a client only gets frames from a key frame on`() {
		val source = FakeSource(listOf(OTHER_FRAME, OTHER_FRAME, KEY_FRAME))
		withServer(source) { port ->
			RtspClient(port).use { client ->
				val nonce = client.challenge()
				client.request("DESCRIBE", nonce = nonce)
				client.request("SETUP", "$URI/track0", listOf(TCP), nonce = nonce)
				client.request("PLAY", nonce = nonce)
				assertEquals(KEY_NAL_TYPE, client.readPacket()[12].toInt() and 0x1F)
			}
		}
	}

	@Test
	fun `a camera that cannot start is an answer, not a dead connection`() {
		withServer(FakeSource(failing = true)) { port ->
			RtspClient(port).use { client ->
				assertEquals(503, client.request("DESCRIBE", nonce = client.challenge()).status)
				assertEquals(200, client.request("OPTIONS").status)
			}
		}
	}

	@Test
	fun `losing the camera ends the clients, so they retry`() {
		val source = FakeSource()
		withServer(source) { port ->
			RtspClient(port).use { client ->
				val nonce = client.challenge()
				client.request("DESCRIBE", nonce = nonce)
				client.request("SETUP", "$URI/track0", listOf(TCP), nonce = nonce)
				client.request("PLAY", nonce = nonce)
				client.readPacket()
				source.fail()
				withContext(Dispatchers.IO) {
					while (true) {
						try {
							client.readPacket()
						} catch (_: Exception) {
							break
						}
					}
				}
			}
			assertEquals(1, source.stops)
		}
	}

	@Test
	fun `udp is refused so that clients fall back to tcp`() {
		withServer(FakeSource()) { port ->
			RtspClient(port).use { client ->
				val nonce = client.challenge()
				client.request("DESCRIBE", nonce = nonce)
				val udp = client.request("SETUP", "$URI/track0", listOf("Transport: RTP/AVP;unicast;client_port=5000-5001"), nonce = nonce)
				assertEquals(461, udp.status)
			}
		}
	}

	@Test
	fun `other paths do not exist`() {
		val source = FakeSource()
		withServer(source) { port ->
			RtspClient(port).use { client ->
				val uri = "rtsp://127.0.0.1/other"
				val nonce = client.challenge()
				assertEquals(404, client.request("DESCRIBE", uri, nonce = nonce).status)
			}
			assertEquals(0, source.starts)
		}
	}

	@Test
	fun `an endless request is cut`() {
		withServer(FakeSource()) { port ->
			Socket("127.0.0.1", port).use { socket ->
				withContext(Dispatchers.IO) {
					socket.soTimeout = 5000
					val junk = ByteArray(1024) { 'a'.code.toByte() }
					val ended =
						try {
							repeat(64) { socket.getOutputStream().write(junk) }
							socket.getInputStream().read() < 0
						} catch (_: SocketException) {
							// A close with unread data arrives as a reset.
							true
						}
					assertTrue(ended)
				}
			}
		}
	}

	/** What really matters: the client that Home Assistant and Frigate are built on understands it, Digest included. */
	@Test
	fun `ffmpeg reads the stream`() {
		val tools = listOf("ffmpeg", "ffprobe").map { tool(it) }
		if (tools.any { it == null }) return
		val annexB = File.createTempFile("marc2d", ".h264")
		try {
			val encoded =
				ProcessBuilder(
					tools[0],
					"-y",
					"-loglevel",
					"error",
					"-f",
					"lavfi",
					"-i",
					"testsrc=size=320x240:rate=15:duration=2",
					"-c:v",
					"libx264",
					"-g",
					"5",
					"-pix_fmt",
					"yuv420p",
					"-f",
					"h264",
					annexB.path,
				).inheritIO().start()
			assertTrue(encoded.waitFor(60, TimeUnit.SECONDS))
			if (encoded.exitValue() != 0) return
			val units = nalUnits(annexB.readBytes())
			val sps = units.first { it[0].toInt() and 0x1F == 7 }
			val pps = units.first { it[0].toInt() and 0x1F == 8 }
			val frames =
				units.filter { it[0].toInt() and 0x1F in listOf(1, 5) }.map { unit ->
					val key = unit[0].toInt() and 0x1F == 5
					H264Frame(if (key) annexBOf(sps, pps, unit) else annexBOf(unit), 0, key)
				}
			withServer(FakeH264File(H264Parameters(sps, pps), frames)) { port ->
				val probe =
					withContext(Dispatchers.IO) {
						val process =
							ProcessBuilder(
								tools[1],
								"-v",
								"error",
								"-rtsp_transport",
								"tcp",
								"-show_entries",
								"stream=codec_name,width,height",
								"-of",
								"csv=p=0",
								"rtsp://marc2d:$PASSWORD@127.0.0.1:$port/camera",
							).redirectErrorStream(true).start()
						assertTrue(process.waitFor(20, TimeUnit.SECONDS))
						process.inputStream.bufferedReader().readText()
					}
				assertTrue("h264,320,240" in probe, probe)
			}
		} finally {
			annexB.delete()
		}
	}

	private class FakeH264File(
		private val parameters: H264Parameters,
		frames: List<H264Frame>,
	) : H264Source {
		private val inner = FakeSource(frames)

		override suspend fun start(
			onFrame: (H264Frame) -> Unit,
			onFailure: () -> Unit,
		): H264Parameters {
			inner.start(onFrame, onFailure)
			return parameters
		}

		override fun requestKeyFrame() = inner.requestKeyFrame()

		override fun stop() = inner.stop()
	}

	private suspend fun waitFor(condition: () -> Boolean) {
		while (!condition()) delay(10.milliseconds)
	}

	private fun tool(name: String): String? =
		System
			.getenv("PATH")
			.orEmpty()
			.split(File.pathSeparator)
			.map { File(it, name) }
			.firstOrNull { it.canExecute() }
			?.path

	private companion object {
		const val PASSWORD = "s3cr3t-Password_1"
		const val URI = "rtsp://127.0.0.1/camera"
		const val TCP = "Transport: RTP/AVP/TCP;unicast;interleaved=0-1"
		const val KEY_NAL_TYPE = 5
		val PARAMETERS = H264Parameters(byteArrayOf(0x67, 0x42, 0x00, 0x1E), byteArrayOf(0x68, 0xCE.toByte()))
		val KEY_FRAME = H264Frame(annexBOf(byteArrayOf(0x65, 1, 2, 3)), 0, true)
		val OTHER_FRAME = H264Frame(annexBOf(byteArrayOf(0x41, 1, 2, 3)), 0, false)

		fun annexBOf(vararg units: ByteArray): ByteArray = units.fold(ByteArray(0)) { all, unit -> all + byteArrayOf(0, 0, 0, 1) + unit }
	}
}
