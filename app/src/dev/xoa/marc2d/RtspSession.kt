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

/** An audio track of the offer: the intercom's sound, or the backchannel for the panel's voice. */
data class RtspAudioTrack(
	val control: String,
	val payloadType: Int,
	val codec: String,
	val sampleRate: Int,
) {
	val frameSamples: Int
		get() = sampleRate / VOICE_FRAMES_PER_SECOND

	fun encode(samples: ShortArray): ByteArray =
		when (codec) {
			"L16" -> encodePcm(samples)
			"PCMA" -> ByteArray(samples.size) { alaw(samples[it]) }
			else -> ByteArray(samples.size) { ulaw(samples[it]) }
		}

	fun decode(
		bytes: ByteArray,
		offset: Int,
		length: Int,
	): ShortArray =
		when (codec) {
			"L16" -> decodePcm(bytes, offset, length)
			"PCMA" -> ShortArray(length) { alawToLinear(bytes[offset + it]) }
			else -> ShortArray(length) { ulawToLinear(bytes[offset + it]) }
		}
}

/** The voice goes in packets of 20 ms, both from the microphone and to the intercom. */
internal const val VOICE_FRAMES_PER_SECOND = 50

/** The H.264 video of the offer, with the parameters it announces, if it does. */
class RtspVideoTrack(
	val control: String,
	val payloadType: Int,
	val parameters: H264Parameters?,
)

/** What the intercom offers: its video and sound, and the backchannel where it plays the panel's voice. */
class RtspOffer(
	val video: RtspVideoTrack?,
	val sound: RtspAudioTrack?,
	val voice: RtspAudioTrack?,
) {
	companion object {
		private val AUDIO_CODECS = setOf("L16", "PCMA", "PCMU")

		fun from(sdp: String): RtspOffer {
			val media = sdp.split(Regex("\r?\n(?=m=)")).filter { it.startsWith("m=") }.map { section -> section.lines().map(String::trim) }
			val audio = media.filter { it.first().startsWith("m=audio") }
			return RtspOffer(
				video = media.firstNotNullOfOrNull(::video),
				sound = audio.filter { "a=sendonly" !in it }.firstNotNullOfOrNull(::audio),
				// The backchannel is the audio the client sends: sendonly in the server's offer, from the client's side.
				voice = audio.filter { "a=sendonly" in it }.firstNotNullOfOrNull(::audio),
			)
		}

		private fun control(lines: List<String>): String? = lines.firstOrNull { it.startsWith("a=control:") }?.substringAfter("a=control:")

		private fun types(lines: List<String>): List<Int> =
			lines
				.first()
				.split(" ")
				.drop(3)
				.mapNotNull(String::toIntOrNull)

		private fun video(lines: List<String>): RtspVideoTrack? {
			if (!lines.first().startsWith("m=video")) return null
			val control = control(lines) ?: return null
			val type = types(lines).firstOrNull { lines.any { line -> line.startsWith("a=rtpmap:$it ") && "H264/" in line.uppercase() } } ?: return null
			val sets =
				lines
					.firstOrNull { it.startsWith("a=fmtp:$type ") }
					?.let { Regex("sprop-parameter-sets=([^;\\s]+)").find(it)?.groupValues?.get(1) }
					?.split(",")
					?.mapNotNull { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
			val parameters = if (sets != null && sets.size >= 2) H264Parameters(sets[0], sets[1]) else null
			return RtspVideoTrack(control, type, parameters)
		}

		private fun audio(lines: List<String>): RtspAudioTrack? {
			val control = control(lines) ?: return null
			return types(lines).firstNotNullOfOrNull { type ->
				val rtpmap = lines.firstOrNull { it.startsWith("a=rtpmap:$type ") }?.substringAfter(" ")
				val (codec, rate) =
					when {
						rtpmap != null -> rtpmap.split("/").let { it[0].uppercase() to (it.getOrNull(1)?.toIntOrNull() ?: return@firstNotNullOfOrNull null) }

						// The static types need no rtpmap.
						type == 0 -> "PCMU" to 8000

						type == 8 -> "PCMA" to 8000

						else -> return@firstNotNullOfOrNull null
					}
				if (codec in AUDIO_CODECS && (rate == 8000 || rate == 16000)) RtspAudioTrack(control, type, codec, rate) else null
			}
		}
	}
}

/** Where a packet of the session came from. */
enum class RtspMedia { Video, Sound }

/**
 * A session with an intercom over RTSP: its video and sound arrive, and the panel's voice goes through its ONVIF backchannel,
 * all interleaved in one connection. A Dahua VTO plays the voice of a SIP call with a regular click, and that of its
 * backchannel cleanly, as DMSS does. It knows nothing of screens, microphones or speakers: the samples it gets and sends can
 * come from anywhere. [receive] runs on one thread; [sendVoice] on another.
 */
class RtspSession private constructor(
	private val socket: Socket,
	private val requests: RtspRequests,
	private val playUri: String,
	val offer: RtspOffer,
	private val channels: Map<Int, RtspMedia>,
	private val voiceChannel: Int?,
) {
	private val voiceWriter = offer.voice?.let { RtpWriter(it.payloadType, it.sampleRate, Random.nextInt(), Random.nextLong(0x1_0000_0000L)) }
	private var samplesSent = 0L
	private var pending = ShortArray(0)
	private var lastKeepAlive = System.nanoTime()

	/** Until the connection ends, which [close] does. Each packet goes with its data, valid only during the call. */
	fun receive(onPacket: (RtspMedia, RtpPacket, ByteArray) -> Unit) {
		try {
			while (true) {
				val (channel, data) = requests.nextPacket() ?: continue
				val media = channels[channel] ?: continue
				val rtp = RtpPacket.parse(data, data.size) ?: continue
				onPacket(media, rtp, data)
				val now = System.nanoTime()
				if (now - lastKeepAlive > KEEP_ALIVE_NANOS) {
					lastKeepAlive = now
					requests.send("GET_PARAMETER", playUri)
				}
			}
		} catch (_: IOException) {
			// The connection is over.
		}
	}

	/** Samples at the backchannel's rate, gone out in packets of 20 ms. Nothing if the intercom has no backchannel. */
	fun sendVoice(samples: ShortArray) {
		val track = offer.voice ?: return
		val channel = voiceChannel ?: return
		val writer = voiceWriter ?: return
		pending += samples
		val size = track.frameSamples
		while (pending.size >= size) {
			val payload = track.encode(pending.copyOfRange(0, size))
			pending = pending.copyOfRange(size, pending.size)
			// At 8 or 16 kHz, 20 ms are a whole number of microseconds: the RTP timestamp advances by exactly the samples sent.
			requests.write(interleaved(channel, writer.packet(payload, samplesSent * MICROS_PER_SECOND / track.sampleRate, false)))
			samplesSent += size
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

	companion object {
		private const val DEFAULT_PORT = 554
		private const val TIMEOUT_MILLIS = 5_000
		private const val KEEP_ALIVE_NANOS = 20_000_000_000L
		const val BACKCHANNEL = "www.onvif.org/ver20/backchannel"

		/** The URL carries the user and the password. It fails with an [IOException] that never includes the URL. */
		fun open(url: String): RtspSession {
			val address =
				try {
					URI(url)
				} catch (_: Exception) {
					throw IOException("Not a URL")
				}
			if (address.scheme != "rtsp" || address.host == null) throw IOException("Not an RTSP URL")
			val credentials = address.rawUserInfo?.let(::credentials)
			val port = if (address.port > 0) address.port else DEFAULT_PORT
			// The request URL is the same without the credentials.
			val bare =
				URI("rtsp", null, address.host, address.port, address.rawPath.ifEmpty { "/" }, null, null).toString() +
					(address.rawQuery?.let { "?$it" } ?: "")
			val socket = Socket()
			try {
				socket.connect(InetSocketAddress(address.host, port), TIMEOUT_MILLIS)
				socket.soTimeout = TIMEOUT_MILLIS
				val requests = RtspRequests(BufferedInputStream(socket.getInputStream()), socket.getOutputStream(), credentials)
				val described = requests.call("DESCRIBE", bare, listOf("Accept" to "application/sdp", "Require" to BACKCHANNEL))
				val offer = RtspOffer.from(described.body)
				val base = described.headers["content-base"] ?: bare
				val channels = mutableMapOf<Int, RtspMedia>()
				var voiceChannel: Int? = null
				val tracks = listOfNotNull(offer.video?.let { it.control to RtspMedia.Video }, offer.sound?.let { it.control to RtspMedia.Sound })
				var next = 0
				for ((control, media) in tracks + listOfNotNull(offer.voice?.let { it.control to null })) {
					val setup =
						requests.call(
							"SETUP",
							controlUri(base, control),
							listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=$next-${next + 1}", "Require" to BACKCHANNEL),
						)
					if (requests.session == null) {
						requests.session = setup.headers["session"]?.substringBefore(";")?.trim() ?: throw IOException("No session")
					}
					// The server may choose its own channel: a Dahua VTO answers 10-11 whatever is asked, and drops the rest.
					val channel = interleavedChannel(setup.headers["transport"])?.takeIf { it in 0..255 } ?: next
					if (media == null) voiceChannel = channel else channels[channel] = media
					next += 2
				}
				if (requests.session == null) throw IOException("Nothing to set up")
				requests.call("PLAY", base, listOf("Range" to "npt=0.000-", "Require" to BACKCHANNEL))
				// From here on the packets wait as long as they take.
				socket.soTimeout = 0
				return RtspSession(socket, requests, base, offer, channels, voiceChannel)
			} catch (e: Exception) {
				socket.close()
				throw e as? IOException ?: IOException(e::class.simpleName)
			}
		}

		/** The user and password of a URL's user info, percent-decoded: a `+` there is a plus, not a space as in a form. */
		fun credentials(rawUserInfo: String): Pair<String, String> {
			val parts = rawUserInfo.split(":", limit = 2).map { URLDecoder.decode(it.replace("+", "%2B"), Charsets.UTF_8) }
			return parts[0] to parts.getOrElse(1) { "" }
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

/** Requests on one RTSP connection, authenticated with Digest or Basic once the server asks, and the packets between them. */
internal class RtspRequests(
	private val input: InputStream,
	private val output: OutputStream,
	private val credentials: Pair<String, String>?,
) {
	private var cseq = 0
	private var challenge: Map<String, String>? = null
	private var basic = false

	@Volatile var session: String? = null

	/** Sends one and waits for its reply: it fails with anything but 2xx. */
	fun call(
		method: String,
		uri: String,
		headers: List<Pair<String, String>> = emptyList(),
	): RtspResponse {
		send(method, uri, headers)
		var response = nextResponse()
		if (response.status == UNAUTHORIZED && credentials != null && challenge == null && !basic) {
			val offered = response.headers["www-authenticate"].orEmpty()
			challenge = digestFields(offered)
			basic = challenge == null && offered.startsWith("Basic", ignoreCase = true)
			send(method, uri, headers)
			response = nextResponse()
		}
		if (response.status !in 200..299) throw IOException("$method: ${response.status}")
		return response
	}

	fun send(
		method: String,
		uri: String,
		headers: List<Pair<String, String>> = emptyList(),
	) {
		val request =
			synchronized(this) {
				cseq++
				val all =
					buildList {
						add("CSeq" to "$cseq")
						add("User-Agent" to "marc2d")
						authorization(method, uri)?.let { add("Authorization" to it) }
						session?.let { add("Session" to it) }
						addAll(headers)
					}
				"$method $uri RTSP/1.0\r\n" + all.joinToString("") { (name, value) -> "$name: $value\r\n" } + "\r\n"
			}
		write(request.toByteArray())
	}

	fun write(bytes: ByteArray) {
		synchronized(output) {
			output.write(bytes)
			output.flush()
		}
	}

	/** The next interleaved packet, with its channel; nothing if a reply came instead, which is read and let go. */
	fun nextPacket(): Pair<Int, ByteArray>? {
		val first = input.read()
		if (first < 0) throw IOException("Connection closed")
		if (first != INTERLEAVED) {
			reply(first)
			return null
		}
		return readInterleaved(input) ?: throw IOException("Connection closed")
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
		val response = digestResponse(user, realm, password, nonce, method, uri)
		return "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
	}

	/** The next reply, letting go of any packet before it. */
	private fun nextResponse(): RtspResponse {
		while (true) {
			val first = input.read()
			if (first < 0) throw IOException("Connection closed")
			if (first == INTERLEAVED) {
				readInterleaved(input) ?: throw IOException("Connection closed")
				continue
			}
			return reply(first)
		}
	}

	private fun reply(first: Int): RtspResponse {
		val statusLine = (first.toChar() + line()).trim()
		val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad reply")
		val headers = rtspHeaders(generateSequence(::line).takeWhile { it.isNotEmpty() }.toList())
		val length = headers["content-length"]?.toIntOrNull() ?: 0
		if (length !in 0..MAX_BODY) throw IOException("Body too long")
		return RtspResponse(status, headers, String(input.readNBytes(length), Charsets.UTF_8))
	}

	private fun line(): String = readRtspLine(input, MAX_BODY) ?: throw IOException("Connection closed")

	private companion object {
		const val UNAUTHORIZED = 401
		const val MAX_BODY = 64 * 1024
	}
}

/**
 * Rebuilds the access units of an H.264 stream from its RTP packets (RFC 6184): single NAL units, STAP-A and FU-A. A unit
 * with a fragment lost is dropped, so the decoder never gets a broken one.
 */
class H264Depacketizer {
	private val units = mutableListOf<ByteArray>()
	private var fragment: ByteArrayOutputStream? = null
	private var lastSequence: Int? = null

	/** What the frame being rebuilt holds so far, to let go of one that never ends. */
	private var frameBytes = 0
	private var dropping = false

	/** The access unit in Annex B once its last packet, the one with the marker, arrives. */
	fun push(
		rtp: RtpPacket,
		data: ByteArray,
	): ByteArray? {
		val previous = lastSequence
		lastSequence = rtp.sequence
		if (previous != null && rtp.sequence != ((previous + 1) and 0xFFFF)) fragment = null
		frameBytes += rtp.payloadLength
		if (frameBytes > MAX_FRAME_BYTES) {
			// A frame larger than any camera sends: an intercom that lost the marker, or worse. The rest of it goes too.
			units.clear()
			fragment = null
			dropping = true
		}
		if (!dropping && rtp.payloadLength > 0) unpack(data, rtp.payloadOffset, rtp.payloadLength)
		if (!rtp.marker) return null
		fragment = null
		frameBytes = 0
		if (dropping) {
			dropping = false
			return null
		}
		if (units.isEmpty()) return null
		val out = ByteArrayOutputStream()
		units.forEach {
			out.write(START_CODE)
			out.write(it)
		}
		units.clear()
		return out.toByteArray()
	}

	private fun unpack(
		data: ByteArray,
		offset: Int,
		length: Int,
	) {
		val header = data[offset].toInt() and 0xFF
		when (data[offset].nalType) {
			in 1..23 -> {
				units += data.copyOfRange(offset, offset + length)
			}

			STAP_A -> {
				var at = offset + 1
				while (at + 2 <= offset + length) {
					val size = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
					at += 2
					if (size == 0 || at + size > offset + length) break
					units += data.copyOfRange(at, at + size)
					at += size
				}
			}

			FU_A -> {
				if (length < 2) return
				val fu = data[offset + 1].toInt() and 0xFF
				if (fu and FU_START != 0) {
					fragment = ByteArrayOutputStream().apply { write((header and 0xE0) or data[offset + 1].nalType) }
				}
				val building = fragment ?: return
				building.write(data, offset + 2, length - 2)
				if (fu and FU_END != 0) {
					units += building.toByteArray()
					fragment = null
				}
			}
		}
	}

	companion object {
		// Far above a 1080p key frame, which is some hundreds of kB.
		private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
		val START_CODE = byteArrayOf(0, 0, 0, 1)

		/** Whether the access unit starts a picture on its own, so that decoding can begin there. */
		fun isKeyFrame(annexB: ByteArray): Boolean = nalStarts(annexB).any { it < annexB.size && annexB[it].nalType == NAL_IDR }
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

internal fun alawToLinear(byte: Byte): Short {
	val value = (byte.toInt() and 0xFF) xor 0x55
	val exponent = (value shr 4) and 0x07
	val mantissa = value and 0x0F
	val magnitude = if (exponent == 0) (mantissa shl 4) + 8 else ((mantissa shl 4) + 0x108) shl (exponent - 1)
	return (if (value and 0x80 != 0) magnitude else -magnitude).toShort()
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

internal fun ulawToLinear(byte: Byte): Short {
	val value = (byte.toInt() and 0xFF) xor 0xFF
	val exponent = (value shr 4) and 0x07
	val magnitude = ((((value and 0x0F) shl 3) + 0x84) shl exponent) - 0x84
	return (if (value and 0x80 != 0) -magnitude else magnitude).toShort()
}
