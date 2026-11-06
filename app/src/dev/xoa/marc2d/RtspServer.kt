package dev.xoa.marc2d

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the server needs from the camera. It sits behind this so the protocol can be tested without a phone. */
interface H264Source {
	/** Turns the camera on. It throws if it cannot, and calls [onFailure] if it stops by itself later. */
	suspend fun start(
		onFrame: (H264Frame) -> Unit,
		onFailure: () -> Unit,
	): H264Parameters

	/** A client that has just joined cannot decode anything until the next key frame. */
	fun requestKeyFrame()

	fun stop()
}

/** The panel's microphone. Without it the stream still works, only with no sound. */
interface AacSource {
	/** It throws if it cannot start, and calls [onFailure] if it stops by itself later. */
	suspend fun start(
		onFrame: (AacFrame) -> Unit,
		onFailure: () -> Unit,
	): AacConfig

	fun stop()
}

/**
 * An RTSP server that only turns the camera on while someone is watching: the camera starts with the first authenticated
 * DESCRIBE, because the SDP needs what the encoder produces, and stops shortly after the last client leaves.
 *
 * Only RTP over the RTSP connection, as Home Assistant and Frigate use by default: it needs no more ports, and a client
 * that falls behind is noticed here, instead of losing video without anyone knowing.
 */
class RtspServer(
	private val password: String,
	private val source: H264Source,
	private val stopDelay: Duration = STOP_DELAY,
	/** Whether the camera is on, so that the panel can show it to whoever is in front. */
	private val onCamera: (Boolean) -> Unit = {},
	/** Nothing if the stream has no sound. */
	private val audio: AacSource? = null,
	/** Whether the microphone is being sent, as the panel shows that differently. */
	private val onSound: (Boolean) -> Unit = {},
	/** How long a connection may stay without a correct password: past it, it only takes a place. */
	private val handshakeTimeout: Duration = HANDSHAKE_TIMEOUT,
) {
	private val random = SecureRandom()
	private val packetizer = H264Packetizer()
	private val clients = AtomicInteger()
	private val lock = Mutex()
	private val viewers = mutableSetOf<Client>()
	private var parameters: H264Parameters? = null

	@Volatile private var audioConfig: AacConfig? = null

	@Volatile private var audioPacketizer: AacPacketizer? = null
	private var users = 0
	private var stopping: Job? = null

	/** Serves until it is cancelled. [onListening] gets the port, which tests choose by asking for 0. */
	suspend fun run(
		port: Int,
		onListening: (Int) -> Unit = {},
	) {
		val server = ServerSocket(port)
		try {
			onListening(server.localPort)
			coroutineScope {
				closeOnCancel(server)
				while (true) {
					val socket =
						try {
							withContext(Dispatchers.IO) { server.accept() }
						} catch (e: SocketException) {
							// Closing it is the only way to stop a blocked accept, which ignores cancellation.
							ensureActive()
							throw e
						}
					if (clients.incrementAndGet() > MAX_CLIENTS) {
						clients.decrementAndGet()
						socket.close()
						continue
					}
					launch(Dispatchers.IO) {
						try {
							Client(socket, this@coroutineScope).serve()
						} finally {
							clients.decrementAndGet()
						}
					}
				}
			}
		} finally {
			server.close()
			stopCamera()
		}
	}

	private fun CoroutineScope.closeOnCancel(closeable: Closeable) {
		launch {
			try {
				awaitCancellation()
			} finally {
				closeable.close()
			}
		}
	}

	private suspend fun acquire(scope: CoroutineScope): H264Parameters =
		lock.withLock {
			stopping?.cancel()
			stopping = null
			val current =
				parameters ?: coroutineScope {
					// At the same time: one after the other, a cold start could miss the time HA gives a picture.
					val sound = async { startSound(scope) }
					try {
						source
							.start(
								onFrame = ::deliver,
								onFailure = { scope.launch { failed() } },
							).also {
								parameters = it
								onCamera(true)
							}
					} catch (e: Throwable) {
						sound.await()
						stopSound()
						throw e
					}.also { sound.await() }
				}
			users++
			current
		}

	private suspend fun release(scope: CoroutineScope) {
		lock.withLock {
			users--
			if (users > 0) return
			stopping =
				scope.launch {
					delay(stopDelay)
					lock.withLock {
						if (users == 0) stopCamera()
					}
				}
		}
	}

	/** A microphone that fails does not take the video with it. */
	private suspend fun startSound(scope: CoroutineScope) {
		val microphone = audio ?: return
		try {
			val config =
				microphone.start(
					onFrame = ::deliverSound,
					onFailure = { scope.launch { lock.withLock { stopSound() } } },
				)
			audioPacketizer = AacPacketizer(config.sampleRate)
			audioConfig = config
			onSound(true)
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			logDisconnect(e)
		}
	}

	private fun stopSound() {
		if (audioConfig != null) {
			audio?.stop()
			onSound(false)
		}
		audioConfig = null
	}

	private fun stopCamera() {
		stopSound()
		if (parameters != null) {
			source.stop()
			onCamera(false)
		}
		parameters = null
	}

	private suspend fun failed() {
		lock.withLock { stopCamera() }
		synchronized(viewers) { viewers.toList() }.forEach { it.close() }
	}

	private fun deliver(frame: H264Frame) {
		val outgoing =
			synchronized(viewers) {
				if (viewers.isEmpty()) return
				Outgoing(false, packetizer.packets(frame), packetizer.senderReportIfDue(frame.timestampUs), frame.keyFrame)
			}
		synchronized(viewers) { viewers.toList() }.forEach { it.offer(outgoing) }
	}

	private fun deliverSound(frame: AacFrame) {
		val writer = audioPacketizer ?: return
		val outgoing =
			synchronized(viewers) {
				if (viewers.isEmpty()) return
				Outgoing(true, listOf(writer.packet(frame)), writer.senderReportIfDue(frame.timestampUs), false)
			}
		synchronized(viewers) { viewers.toList() }.forEach { it.offer(outgoing) }
	}

	private class Outgoing(
		val isSound: Boolean,
		val packets: List<ByteArray>,
		val report: ByteArray?,
		val keyFrame: Boolean,
	)

	private inner class Client(
		private val socket: Socket,
		private val scope: CoroutineScope,
	) {
		private val nonce = randomHex()
		private val session = randomHex()
		private val output: OutputStream = socket.getOutputStream().buffered()
		private val queue = Channel<Outgoing>(MAX_QUEUED_FRAMES)
		private var videoChannel = 0

		// Nothing until the client asks for the sound: not every client wants it.
		@Volatile private var soundChannel: Int? = null
		private var acquired = false
		private var playing = false
		private var authorized = false
		private var failures = 0

		// Read by the camera's and the microphone's threads.
		@Volatile private var sawKeyFrame = false

		suspend fun serve() {
			try {
				socket.soTimeout = handshakeTimeout.inWholeMilliseconds.toInt()
				val input = BufferedInputStream(socket.getInputStream())
				coroutineScope {
					val watcher = launch { closeOnCancel(socket) }
					val sender = launch { sendFrames() }
					try {
						while (true) {
							val request = readRequest(input) ?: break
							if (!answer(request)) break
						}
					} finally {
						sender.cancel()
						watcher.cancel()
					}
				}
			} catch (e: IOException) {
				if (e !is SocketException) logDisconnect(e)
			} finally {
				synchronized(viewers) { viewers.remove(this) }
				close()
				if (acquired) withContext(NonCancellable) { release(scope) }
			}
		}

		fun offer(outgoing: Outgoing) {
			if (!sawKeyFrame && !outgoing.keyFrame) return
			sawKeyFrame = true
			if (outgoing.isSound && soundChannel == null) return
			// Waiting for it would hold back the rest, and dropping frames would break its video until the next key frame.
			if (queue.trySend(outgoing).isFailure) close()
		}

		fun close() {
			try {
				socket.close()
			} catch (_: IOException) {
				// It is closing anyway.
			}
		}

		private suspend fun sendFrames() {
			for (outgoing in queue) {
				val channel = if (outgoing.isSound) soundChannel ?: continue else videoChannel
				runInterruptible(Dispatchers.IO) {
					try {
						synchronized(output) {
							outgoing.packets.forEach { writeInterleaved(channel, it) }
							// RTCP goes on the channel after its track's.
							outgoing.report?.let { writeInterleaved(channel + 1, it) }
							output.flush()
						}
					} catch (_: IOException) {
						close()
					}
				}
			}
		}

		private fun writeInterleaved(
			channel: Int,
			packet: ByteArray,
		) {
			output.write(INTERLEAVED)
			output.write(channel)
			output.write(packet.size shr 8)
			output.write(packet.size and 0xFF)
			output.write(packet)
		}

		/** The next request, skipping the RTCP that clients send over the same connection. */
		private fun readRequest(input: InputStream): RtspRequest? {
			while (true) {
				input.mark(1)
				val first = input.read()
				if (first < 0) return null
				if (first == INTERLEAVED) {
					input.read()
					val high = input.read()
					val low = input.read()
					if (low < 0) return null
					input.skipNBytes((high shl 8 or low).toLong())
					continue
				}
				input.reset()
				return parseRequest(input)
			}
		}

		/** Whether to keep the connection. */
		private suspend fun answer(request: RtspRequest): Boolean {
			val cseq = request.headers["cseq"]
			if (cseq == null) {
				respond(BAD_REQUEST, null)
				return false
			}
			if (request.method == "OPTIONS") {
				respond(OK, cseq, listOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, GET_PARAMETER, TEARDOWN"))
				return true
			}
			if (!isAuthorized(request)) {
				if (request.headers["authorization"] != null) {
					// A wrong password, not the first request that asks for one: each costs time, and a few end the connection.
					failures++
					delay(FAILURE_DELAY)
				}
				respond(UNAUTHORIZED, cseq, listOf("WWW-Authenticate" to "Digest realm=\"$REALM\", nonce=\"$nonce\""))
				return failures < MAX_FAILURES
			}
			if (!authorized) {
				authorized = true
				socket.soTimeout = SESSION_READ_TIMEOUT
			}
			if (!request.isForTheCamera()) {
				respond(NOT_FOUND, cseq)
				return true
			}
			return when (request.method) {
				"DESCRIBE" -> {
					describe(request, cseq)
				}

				"SETUP" -> {
					setup(request, cseq)
				}

				"PLAY" -> {
					play(cseq)
				}

				"GET_PARAMETER", "SET_PARAMETER" -> {
					respond(OK, cseq, listOf(sessionHeader()))
					true
				}

				"TEARDOWN" -> {
					respond(OK, cseq, listOf(sessionHeader()))
					false
				}

				else -> {
					respond(NOT_ALLOWED, cseq)
					true
				}
			}
		}

		private suspend fun describe(
			request: RtspRequest,
			cseq: String,
		): Boolean {
			val parameters = acquireOrNull()
			if (parameters == null) {
				respond(UNAVAILABLE, cseq)
				return true
			}
			val body = sdp(parameters, audioConfig)
			respond(
				OK,
				cseq,
				listOf(
					"Content-Type" to "application/sdp",
					"Content-Base" to "${request.uri.removeSuffix("/")}/",
					"Content-Length" to body.toByteArray().size.toString(),
				),
				body,
			)
			return true
		}

		private suspend fun setup(
			request: RtspRequest,
			cseq: String,
		): Boolean {
			val transport = request.headers["transport"].orEmpty()
			if ("TCP" !in transport.uppercase()) {
				respond(UNSUPPORTED_TRANSPORT, cseq)
				return true
			}
			if (acquireOrNull() == null) {
				respond(UNAVAILABLE, cseq)
				return true
			}
			val isSound = request.isForTheSound()
			if (isSound && audioConfig == null) {
				respond(NOT_FOUND, cseq)
				return true
			}
			val channel =
				Regex("interleaved=(\\d+)")
					.find(transport)
					?.groupValues
					?.get(1)
					?.toIntOrNull()
					?.takeIf { it in 0..254 } ?: if (isSound) DEFAULT_SOUND_CHANNEL else 0
			if (isSound) soundChannel = channel else videoChannel = channel
			respond(OK, cseq, listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}", sessionHeader()))
			return true
		}

		private fun play(cseq: String): Boolean {
			if (!acquired) {
				respond(WRONG_STATE, cseq)
				return true
			}
			respond(OK, cseq, listOf(sessionHeader(), "Range" to "npt=now-"))
			// From here only the writes tell if the client is gone: not all of them send anything over TCP while playing.
			socket.soTimeout = 0
			if (!playing) {
				playing = true
				synchronized(viewers) { viewers.add(this) }
				source.requestKeyFrame()
			}
			return true
		}

		private suspend fun acquireOrNull(): H264Parameters? {
			if (acquired) return parameters
			return try {
				acquire(scope).also { acquired = true }
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				logDisconnect(e)
				null
			}
		}

		private fun isAuthorized(request: RtspRequest): Boolean {
			val digest = digestFields(request.headers["authorization"] ?: return false) ?: return false
			if (digest["username"] != RTSP_USER || digest["realm"] != REALM || digest["nonce"] != nonce) return false
			val uri = digest["uri"] ?: return false
			val response = digest["response"] ?: return false
			val expected = md5("${md5("$RTSP_USER:$REALM:$password")}:$nonce:${md5("${request.method}:$uri")}")
			return MessageDigest.isEqual(expected.toByteArray(), response.lowercase().toByteArray())
		}

		private fun sessionHeader() = "Session" to "$session;timeout=$SESSION_TIMEOUT_SECONDS"

		private fun respond(
			status: String,
			cseq: String?,
			headers: List<Pair<String, String>> = emptyList(),
			body: String = "",
		) {
			val head =
				buildString {
					append("RTSP/1.0 $status\r\n")
					if (cseq != null) append("CSeq: $cseq\r\n")
					headers.forEach { (name, value) -> append("$name: $value\r\n") }
					append("\r\n")
				}
			synchronized(output) {
				output.write((head + body).toByteArray())
				output.flush()
			}
		}
	}

	private fun randomHex(): String {
		val bytes = ByteArray(16)
		random.nextBytes(bytes)
		return bytes.joinToString("") { "%02x".format(it) }
	}

	private fun logDisconnect(e: Exception) {
		onProblem(e::class.simpleName ?: "error")
	}

	/** Never with the request: it carries the digest of the password. */
	var onProblem: (String) -> Unit = {}

	companion object {
		const val RTSP_USER = "marc2d"
		private const val REALM = "marc2d"
		private const val INTERLEAVED = '$'.code
		private const val OK = "200 OK"
		private const val BAD_REQUEST = "400 Bad Request"
		private const val UNAUTHORIZED = "401 Unauthorized"
		private const val NOT_FOUND = "404 Not Found"
		private const val NOT_ALLOWED = "405 Method Not Allowed"
		private const val WRONG_STATE = "455 Method Not Valid In This State"
		private const val UNSUPPORTED_TRANSPORT = "461 Unsupported Transport"
		private const val UNAVAILABLE = "503 Service Unavailable"
		private const val MAX_CLIENTS = 8

		// About 4 seconds, counting the frames of both tracks.
		private const val MAX_QUEUED_FRAMES = 120
		private const val DEFAULT_SOUND_CHANNEL = 2
		private const val SESSION_READ_TIMEOUT = 30_000
		private const val MAX_FAILURES = 3
		private val FAILURE_DELAY = 1.seconds
		private val HANDSHAKE_TIMEOUT = 5.seconds
		private const val SESSION_TIMEOUT_SECONDS = 60

		/** Clients often connect twice in a row to look at the stream first: the camera should not blink between them. */
		private val STOP_DELAY = 10.seconds
	}
}

internal class RtspRequest(
	val method: String,
	val uri: String,
	val headers: Map<String, String>,
) {
	fun isForTheCamera(): Boolean {
		val path =
			try {
				URI(uri).path.orEmpty()
			} catch (_: URISyntaxException) {
				return false
			}
		return path.trimEnd('/') in listOf(RTSP_PATH, "$RTSP_PATH/$RTSP_TRACK", "$RTSP_PATH/$RTSP_SOUND_TRACK")
	}

	fun isForTheSound(): Boolean = uri.trimEnd('/').endsWith("/$RTSP_SOUND_TRACK")
}

/** Nothing if the connection ends. It limits sizes because anyone on the network can connect before authenticating. */
internal fun parseRequest(input: InputStream): RtspRequest? {
	val lines = mutableListOf<String>()
	var total = 0
	while (true) {
		val line = readLine(input) ?: return null
		if (line.isEmpty()) break
		total += line.length
		if (total > MAX_REQUEST) throw IOException("Request too long")
		lines += line
	}
	val parts = lines.firstOrNull()?.split(" ") ?: throw IOException("Empty request")
	if (parts.size != 3) throw IOException("Bad request line")
	val headers =
		lines
			.drop(1)
			.mapNotNull { line ->
				val colon = line.indexOf(':')
				if (colon < 0) null else line.take(colon).trim().lowercase() to line.substring(colon + 1).trim()
			}.toMap()
	val length = headers["content-length"]?.toLongOrNull() ?: 0
	if (length !in 0..MAX_REQUEST) throw IOException("Body too long")
	input.skipNBytes(length)
	return RtspRequest(parts[0], parts[1], headers)
}

private fun readLine(input: InputStream): String? {
	val line = ByteArrayOutputStream()
	while (true) {
		val byte = input.read()
		if (byte < 0) return null
		if (byte == '\n'.code) break
		if (line.size() >= MAX_REQUEST) throw IOException("Line too long")
		if (byte != '\r'.code) line.write(byte)
	}
	return line.toString(Charsets.UTF_8)
}

internal fun digestFields(header: String): Map<String, String>? {
	if (!header.startsWith("Digest ", ignoreCase = true)) return null
	return Regex("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))")
		.findAll(header.substring("Digest ".length))
		.associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
}

internal fun md5(text: String): String =
	MessageDigest
		.getInstance("MD5")
		.digest(text.toByteArray())
		.joinToString("") { "%02x".format(it) }

internal fun sdp(
	parameters: H264Parameters,
	audio: AacConfig? = null,
): String {
	val encoder = Base64.getEncoder()
	val profile =
		parameters.sps
			.drop(1)
			.take(3)
			.joinToString("") { "%02x".format(it) }
	val sets = "${encoder.encodeToString(parameters.sps)},${encoder.encodeToString(parameters.pps)}"
	val video =
		listOf(
			"v=0",
			"o=- 0 0 IN IP4 0.0.0.0",
			"s=marc2d",
			"c=IN IP4 0.0.0.0",
			"t=0 0",
			"a=control:*",
			"m=video 0 RTP/AVP $RTP_PAYLOAD_TYPE",
			"a=rtpmap:$RTP_PAYLOAD_TYPE H264/90000",
			"a=fmtp:$RTP_PAYLOAD_TYPE packetization-mode=1;profile-level-id=$profile;sprop-parameter-sets=$sets",
			"a=control:$RTSP_TRACK",
		)
	val sound =
		audio
			?.let {
				val config = it.config.joinToString("") { byte -> "%02x".format(byte) }
				listOf(
					"m=audio 0 RTP/AVP $RTP_AUDIO_PAYLOAD_TYPE",
					"a=rtpmap:$RTP_AUDIO_PAYLOAD_TYPE mpeg4-generic/${it.sampleRate}/${it.channels}",
					"a=fmtp:$RTP_AUDIO_PAYLOAD_TYPE streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=$config",
					"a=control:$RTSP_SOUND_TRACK",
				)
			}.orEmpty()
	return (video + sound + "").joinToString("\r\n")
}

const val RTSP_PORT = 8554
const val RTSP_PATH = "/camera"
private const val RTSP_TRACK = "track0"
private const val RTSP_SOUND_TRACK = "track1"
private const val MAX_REQUEST = 16 * 1024
