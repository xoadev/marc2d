package dev.xoa.marc2d

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import kotlin.random.Random

/** The intercom's track for the panel's voice, from the `sendonly` audio of its offer. */
data class BackchannelTrack(
	val control: String,
	val payloadType: Int,
	val codec: String,
	val sampleRate: Int,
) {
	/** One packet: 20 ms. */
	val frameSamples: Int
		get() = sampleRate / FRAMES_PER_SECOND

	fun encode(samples: ShortArray): ByteArray =
		when (codec) {
			"L16" -> encodePcm(samples, littleEndian = false)
			"PCMA" -> ByteArray(samples.size) { alaw(samples[it]) }
			else -> ByteArray(samples.size) { ulaw(samples[it]) }
		}

	companion object {
		private const val FRAMES_PER_SECOND = 50
		val CODECS = setOf("L16", "PCMA", "PCMU")

		/** The first `sendonly` audio track of a codec the panel can send, if there is one. */
		fun from(sdp: String): BackchannelTrack? =
			sdp
				.split(Regex("\r?\n(?=m=)"))
				.filter { it.startsWith("m=audio") }
				.firstNotNullOfOrNull { media ->
					val lines = media.lines().map(String::trim)
					if ("a=sendonly" !in lines) return@firstNotNullOfOrNull null
					val types =
						lines
							.first()
							.split(" ")
							.drop(3)
							.mapNotNull(String::toIntOrNull)
					val control = lines.firstOrNull { it.startsWith("a=control:") }?.substringAfter("a=control:") ?: return@firstNotNullOfOrNull null
					types.firstNotNullOfOrNull { type -> track(lines, type, control) }
				}

		private fun track(
			lines: List<String>,
			type: Int,
			control: String,
		): BackchannelTrack? {
			val rtpmap = lines.firstOrNull { it.startsWith("a=rtpmap:$type ") }?.substringAfter(" ")
			val (codec, rate) =
				when {
					rtpmap != null -> rtpmap.split("/").let { it[0].uppercase() to (it.getOrNull(1)?.toIntOrNull() ?: return null) }

					// The static types need no rtpmap.
					type == 0 -> "PCMU" to 8000

					type == 8 -> "PCMA" to 8000

					else -> return null
				}
			return if (codec in CODECS && (rate == 8000 || rate == 16000)) BackchannelTrack(control, type, codec, rate) else null
		}
	}
}

/**
 * The panel's voice into an intercom over RTSP, through the ONVIF backchannel: a Dahua VTO plays what arrives there cleanly,
 * while it clicks with the audio of a SIP call. The RTP goes interleaved in the RTSP connection, which keeps the session
 * alive. Opened, used and closed from one thread; the replies it gets meanwhile are read on another.
 */
class RtspBackchannel private constructor(
	private val socket: Socket,
	private val output: OutputStream,
	private val requests: RtspRequests,
	private val playUri: String,
	val track: BackchannelTrack,
) {
	private val ssrc = Random.nextInt()
	private var sequence = Random.nextInt(0x10000)
	private var timestamp = Random.nextLong(0x1_0000_0000L)
	private var pending = ShortArray(0)
	private var lastKeepAlive = System.nanoTime()

	init {
		Thread({ drain() }, "marc2d-backchannel-in").apply { isDaemon = true }.start()
	}

	/** The microphone's samples, at the track's rate, in packets of 20 ms. */
	fun send(samples: ShortArray) {
		pending += samples
		val size = track.frameSamples
		while (pending.size >= size) {
			val payload = track.encode(pending.copyOfRange(0, size))
			pending = pending.copyOfRange(size, pending.size)
			val rtp = rtpHeader(track.payloadType, sequence, timestamp, ssrc) + payload
			val frame = byteArrayOf('$'.code.toByte(), 0, (rtp.size shr 8).toByte(), rtp.size.toByte()) + rtp
			synchronized(output) {
				output.write(frame)
				output.flush()
			}
			sequence = (sequence + 1) and 0xFFFF
			timestamp = (timestamp + size) and 0xFFFFFFFFL
		}
		val now = System.nanoTime()
		if (now - lastKeepAlive > KEEP_ALIVE_NANOS) {
			lastKeepAlive = now
			requests.send("GET_PARAMETER", playUri)
		}
	}

	fun close() {
		try {
			requests.send("TEARDOWN", playUri)
		} catch (_: IOException) {
			// It is going anyway.
		}
		socket.close()
	}

	/** Whatever the intercom sends now, replies and RTCP, is not needed: it only must not fill the connection. */
	private fun drain() {
		val buffer = ByteArray(DRAIN_BUFFER)
		try {
			while (socket.getInputStream().read(buffer) >= 0) {
				// Nothing to do with it.
			}
		} catch (_: IOException) {
			// The connection is over.
		}
	}

	companion object {
		private const val DEFAULT_PORT = 554
		private const val TIMEOUT_MILLIS = 5_000
		private const val DRAIN_BUFFER = 4096
		private const val KEEP_ALIVE_NANOS = 20_000_000_000L
		const val BACKCHANNEL = "www.onvif.org/ver20/backchannel"

		/** The URL carries the user and the password. It fails with an [IOException] that never includes the URL. */
		fun open(url: String): RtspBackchannel {
			val address = URI(url)
			if (address.scheme != "rtsp" || address.host == null) throw IOException("Not an RTSP URL")
			val credentials =
				address.rawUserInfo?.split(":", limit = 2)?.let { parts ->
					parts.map { URLDecoder.decode(it, Charsets.UTF_8) }.let { it[0] to it.getOrElse(1) { "" } }
				}
			val port = if (address.port > 0) address.port else DEFAULT_PORT
			// The request URL is the same without the credentials.
			val bare =
				URI("rtsp", null, address.host, address.port, address.rawPath.ifEmpty { "/" }, null, null).toString() +
					(address.rawQuery?.let { "?$it" } ?: "")
			val socket = Socket()
			try {
				socket.connect(InetSocketAddress(address.host, port), TIMEOUT_MILLIS)
				socket.soTimeout = TIMEOUT_MILLIS
				val output = socket.getOutputStream()
				val requests = RtspRequests(BufferedInputStream(socket.getInputStream()), output, credentials)
				val described = requests.call("DESCRIBE", bare, listOf("Accept" to "application/sdp", "Require" to BACKCHANNEL))
				val track = BackchannelTrack.from(described.body) ?: throw IOException("No backchannel")
				val base = described.headers["content-base"] ?: bare
				val setup =
					requests.call(
						"SETUP",
						controlUri(base, track.control),
						listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1", "Require" to BACKCHANNEL),
					)
				requests.session = setup.headers["session"]?.substringBefore(";")?.trim() ?: throw IOException("No session")
				requests.call("PLAY", base, listOf("Range" to "npt=0.000-", "Require" to BACKCHANNEL))
				// From here on the replies are drained, not read.
				socket.soTimeout = 0
				return RtspBackchannel(socket, output, requests, base, track)
			} catch (e: Exception) {
				socket.close()
				throw e as? IOException ?: IOException(e::class.simpleName)
			}
		}

		/** As RFC 2326 and the cameras do it: an absolute control stays, a relative one goes after the base. */
		fun controlUri(
			base: String,
			control: String,
		): String =
			when {
				control.startsWith("rtsp://") -> control
				control == "*" -> base
				else -> base.trimEnd('/') + "/" + control
			}
	}
}

internal class RtspResponse(
	val status: Int,
	val headers: Map<String, String>,
	val body: String,
)

/** Requests on one RTSP connection, authenticated with Digest or Basic once the server asks. */
internal class RtspRequests(
	private val input: InputStream,
	private val output: OutputStream,
	private val credentials: Pair<String, String>?,
) {
	private var cseq = 0
	private var challenge: Map<String, String>? = null
	private var basic = false
	var session: String? = null

	/** Sends one and waits for its reply: it fails with anything but 2xx. */
	fun call(
		method: String,
		uri: String,
		headers: List<Pair<String, String>> = emptyList(),
	): RtspResponse {
		send(method, uri, headers)
		var response = read()
		if (response.status == UNAUTHORIZED && credentials != null && challenge == null && !basic) {
			val offered = response.headers["www-authenticate"].orEmpty()
			challenge = digestFields(offered)
			basic = challenge == null && offered.startsWith("Basic", ignoreCase = true)
			send(method, uri, headers)
			response = read()
		}
		if (response.status !in 200..299) throw IOException("$method: ${response.status}")
		return response
	}

	fun send(
		method: String,
		uri: String,
		headers: List<Pair<String, String>> = emptyList(),
	) {
		cseq++
		val all =
			buildList {
				add("CSeq" to "$cseq")
				add("User-Agent" to "marc2d")
				authorization(method, uri)?.let { add("Authorization" to it) }
				session?.let { add("Session" to it) }
				addAll(headers)
			}
		val request = "$method $uri RTSP/1.0\r\n" + all.joinToString("") { (name, value) -> "$name: $value\r\n" } + "\r\n"
		synchronized(output) {
			output.write(request.toByteArray())
			output.flush()
		}
	}

	private fun authorization(
		method: String,
		uri: String,
	): String? {
		val (user, password) = credentials ?: return null
		if (basic) return "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
		val fields = challenge ?: return null
		val realm = fields["realm"].orEmpty()
		val nonce = fields["nonce"].orEmpty()
		val ha1 = md5("$user:$realm:$password")
		val ha2 = md5("$method:$uri")
		val response = md5("$ha1:$nonce:$ha2")
		return "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
	}

	/** The next reply, skipping any interleaved packet before it. */
	private fun read(): RtspResponse {
		while (true) {
			val first = input.read()
			if (first < 0) throw IOException("Connection closed")
			if (first == '$'.code) {
				input.read()
				val length = (input.read() shl 8) or input.read()
				input.skipNBytes(length.toLong())
				continue
			}
			val statusLine = (first.toChar() + (line() ?: throw IOException("Connection closed"))).trim()
			val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad reply")
			val headers = mutableMapOf<String, String>()
			while (true) {
				val header = line() ?: throw IOException("Connection closed")
				if (header.isEmpty()) break
				val colon = header.indexOf(':')
				if (colon > 0) headers[header.take(colon).trim().lowercase()] = header.substring(colon + 1).trim()
			}
			val length = headers["content-length"]?.toIntOrNull() ?: 0
			if (length !in 0..MAX_BODY) throw IOException("Body too long")
			return RtspResponse(status, headers, String(input.readNBytes(length), Charsets.UTF_8))
		}
	}

	private fun line(): String? {
		val line = ByteArrayOutputStream()
		while (true) {
			val byte = input.read()
			if (byte < 0) return null
			if (byte == '\n'.code) break
			if (line.size() >= MAX_BODY) throw IOException("Line too long")
			if (byte != '\r'.code) line.write(byte)
		}
		return line.toString(Charsets.UTF_8)
	}

	private companion object {
		const val UNAUTHORIZED = 401
		const val MAX_BODY = 64 * 1024
	}
}

/** G.711 A-law, as ITU-T G.711 and its reference code. */
internal fun alaw(sample: Short): Byte {
	var value = sample.toInt()
	val sign = if (value >= 0) 0xD5 else 0x55
	if (value < 0) value = -value - 1
	value = minOf(value, 0x7FFF)
	val exponent = if (value < 256) 0 else (31 - Integer.numberOfLeadingZeros(value)) - 7
	val mantissa = if (exponent == 0) (value shr 4) and 0x0F else (value shr (exponent + 3)) and 0x0F
	return (((exponent shl 4) or mantissa) xor sign).toByte()
}

/** G.711 µ-law, as ITU-T G.711 and its reference code. */
internal fun ulaw(sample: Short): Byte {
	var value = sample.toInt()
	val sign = if (value < 0) 0x80 else 0
	if (value < 0) value = -value
	value = minOf(value, 32_635) + 0x84
	val exponent = (31 - Integer.numberOfLeadingZeros(value)) - 7
	val mantissa = (value shr (exponent + 3)) and 0x0F
	return ((sign or (exponent shl 4) or mantissa) xor 0xFF).toByte()
}
