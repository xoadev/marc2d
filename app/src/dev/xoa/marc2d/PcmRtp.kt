package dev.xoa.marc2d

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Uncompressed audio that an intercom sends however the call was negotiated, and that Linphone cannot play: some send it
 * with their own name for it. Dahua, for one, offers `PCM/16000` in little-endian, while the standard L16 is big-endian.
 */
data class PcmFormat(
	val sampleRate: Int,
	val littleEndian: Boolean,
) {
	/** One packet: 20 ms, as intercoms use. */
	val frameSamples: Int
		get() = sampleRate / FRAMES_PER_SECOND

	companion object {
		const val STANDARD = "standard"
		private const val FRAMES_PER_SECOND = 50
		private val FORMATS =
			mapOf(
				"pcm_le_16000" to PcmFormat(16_000, true),
				"pcm_le_8000" to PcmFormat(8_000, true),
				"l16_16000" to PcmFormat(16_000, false),
				"l16_8000" to PcmFormat(8_000, false),
			)

		/** Nothing for the standard audio, which Linphone negotiates and plays: also for a value this version does not know. */
		fun parse(value: String): PcmFormat? = FORMATS[value]
	}
}

fun decodePcm(
	bytes: ByteArray,
	offset: Int,
	length: Int,
	littleEndian: Boolean,
): ShortArray =
	ShortArray(length / 2) { i ->
		val first = bytes[offset + 2 * i].toInt() and 0xFF
		val second = bytes[offset + 2 * i + 1].toInt() and 0xFF
		(if (littleEndian) (second shl 8) or first else (first shl 8) or second).toShort()
	}

fun encodePcm(
	samples: ShortArray,
	littleEndian: Boolean,
): ByteArray {
	val bytes = ByteArray(samples.size * 2)
	samples.forEachIndexed { i, sample ->
		val high = (sample.toInt() shr 8).toByte()
		val low = sample.toByte()
		bytes[2 * i] = if (littleEndian) low else high
		bytes[2 * i + 1] = if (littleEndian) high else low
	}
	return bytes
}

class RtpPacket(
	val payloadType: Int,
	val sequence: Int,
	val timestamp: Long,
	val payloadOffset: Int,
	val payloadLength: Int,
) {
	companion object {
		private const val HEADER = 12

		/** Nothing if it is not RTP version 2, or its lengths do not add up. */
		fun parse(
			bytes: ByteArray,
			length: Int,
		): RtpPacket? {
			if (length < HEADER || (bytes[0].toInt() and 0xC0) != 0x80) return null
			val csrcs = bytes[0].toInt() and 0x0F
			var offset = HEADER + 4 * csrcs
			if (bytes[0].toInt() and 0x10 != 0) {
				if (length < offset + 4) return null
				val words = ((bytes[offset + 2].toInt() and 0xFF) shl 8) or (bytes[offset + 3].toInt() and 0xFF)
				offset += 4 + 4 * words
			}
			var end = length
			if (bytes[0].toInt() and 0x20 != 0) end -= bytes[length - 1].toInt() and 0xFF
			if (end < offset) return null
			val sequence = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
			val timestamp =
				(0 until 4).fold(0L) { all, i -> (all shl 8) or (bytes[4 + i].toLong() and 0xFF) }
			return RtpPacket(bytes[1].toInt() and 0x7F, sequence, timestamp, offset, end - offset)
		}
	}
}

/**
 * A socket on [port] that gets what arrives there instead of the one already bound to it, which has to allow address
 * reuse too, as Linphone's does: on Linux the last socket bound takes it. Android does not support SO_REUSEPORT on a
 * [DatagramSocket], and it is not needed.
 */
fun takeOverUdpPort(port: Int): DatagramSocket =
	DatagramSocket(null).apply {
		reuseAddress = true
		bind(InetSocketAddress(port))
	}

/** Whether [sequence] comes after [last], with the 16-bit counter going round. */
fun isNewer(
	sequence: Int,
	last: Int,
): Boolean {
	val distance = (sequence - last) and 0xFFFF
	return distance in 1 until 0x8000
}

/**
 * The intercom's uncompressed audio over RTP, beside Linphone, which keeps the call and the video. It shares Linphone's audio
 * port: the last socket bound there with address reuse is the one that gets what arrives.
 *
 * What to send, and where, is learnt from what arrives: the intercom's packets come from where it expects ours, with the
 * payload type it uses. Packets of the codec Linphone negotiated, or too short to be audio, are not it. Ours copy the size
 * and the timestamp step of its packets: a Dahua VTO sends 40 ms with a step that does not match its sample rate.
 */
class PcmRtpLink(
	private val socket: DatagramSocket,
	private val format: PcmFormat,
	private val negotiatedPayloadType: Int,
	private val play: (ShortArray) -> Unit,
) {
	@Volatile private var remote: SocketAddress? = null

	@Volatile private var payloadType: Int? = null

	/** The intercom's packets: how many samples, and how far apart their timestamps usually are. */
	@Volatile private var packetSamples = 0

	@Volatile private var timestampStep = 0L
	private val stepsSeen = mutableListOf<Long>()
	private var firstSequence = 0
	private var firstTimestamp = 0L

	private var last: Int? = null
	private var lastTimestamp: Long? = null

	private var pending = ShortArray(0)
	private var sequenceOut = Random.nextInt(0x10000)
	private var timestampOut = Random.nextLong(0x1_0000_0000L)
	private val ssrc = Random.nextInt()

	@Volatile private var arrived = 0

	@Volatile private var played = 0

	@Volatile private var sent = 0

	@Volatile private var micSamples = 0L

	@Volatile private var micFirstNanos = 0L

	@Volatile private var micLastNanos = 0L

	@Volatile private var longestMicGapMillis = 0L

	@Volatile private var lost = 0

	@Volatile private var late = 0

	@Volatile private var firstMillis = 0L

	@Volatile private var lastMillis = 0L

	@Volatile private var longestGapMillis = 0L

	private val typesArrived = ConcurrentHashMap.newKeySet<Int>()

	val isHeard: Boolean
		get() = payloadType != null

	/** What happened in the call, to tell from the log which way the audio got lost. */
	val summary: String
		get() =
			"$arrived RTP packets arrived, of types ${typesArrived.sorted()}; $played played as type $payloadType, " +
				"of $packetSamples samples a step of $timestampStep (${averageStep()} on average), $lost lost, $late late, " +
				"over ${lastMillis - firstMillis} ms with gaps up to $longestGapMillis ms; $sent sent, " +
				"the microphone at ${micRate()} Hz with gaps up to $longestMicGapMillis ms"

	/** Until the socket closes. */
	fun receive() {
		val buffer = ByteArray(MAX_PACKET)
		while (true) {
			val packet = DatagramPacket(buffer, buffer.size)
			try {
				socket.receive(packet)
			} catch (_: SocketException) {
				return
			}
			val rtp = RtpPacket.parse(buffer, packet.length) ?: continue
			arrived++
			typesArrived.add(rtp.payloadType)
			if (!isTheirAudio(rtp)) continue
			if (payloadType == null) {
				remote = packet.socketAddress
				payloadType = rtp.payloadType
			}
			if (rtp.payloadType != payloadType) continue
			val previous = last
			// A packet that arrives late would only play out of place.
			if (previous != null && !isNewer(rtp.sequence, previous)) {
				late++
				continue
			}
			val previousTimestamp = lastTimestamp
			if (previous != null) lost += ((rtp.sequence - previous) and 0xFFFF) - 1
			if (previousTimestamp == null) {
				firstSequence = rtp.sequence
				firstTimestamp = rtp.timestamp
			}
			// The VTO's steps vary from one packet to the next: the usual one is what each of its packets lasts.
			if (stepsSeen.size < STEPS_TO_LEARN && previousTimestamp != null && rtp.sequence == ((previous ?: 0) + 1) and 0xFFFF) {
				stepsSeen += (rtp.timestamp - previousTimestamp) and 0xFFFFFFFFL
				if (stepsSeen.size == STEPS_TO_LEARN) {
					packetSamples = rtp.payloadLength / 2
					timestampStep = stepsSeen.sorted()[STEPS_TO_LEARN / 2]
				}
			}
			last = rtp.sequence
			lastTimestamp = rtp.timestamp
			val now = System.currentTimeMillis()
			if (firstMillis == 0L) firstMillis = now else longestGapMillis = maxOf(longestGapMillis, now - lastMillis)
			lastMillis = now
			played++
			play(decodePcm(buffer, rtp.payloadOffset, rtp.payloadLength, format.littleEndian))
		}
	}

	private fun micRate(): Long {
		val nanos = micLastNanos - micFirstNanos
		return if (nanos <= 0) 0 else micSamples * 1_000_000_000L / nanos
	}

	private fun averageStep(): Long {
		val packets = ((last ?: firstSequence) - firstSequence) and 0xFFFF
		return if (packets == 0) 0 else (((lastTimestamp ?: firstTimestamp) - firstTimestamp) and 0xFFFFFFFFL) / packets
	}

	private fun isTheirAudio(rtp: RtpPacket): Boolean =
		rtp.payloadType != negotiatedPayloadType &&
			rtp.payloadType in DYNAMIC_TYPES &&
			rtp.payloadLength >= MIN_AUDIO_BYTES &&
			rtp.payloadLength % 2 == 0

	/** Sends the microphone in packets like the intercom's, once it has sent a few in a row. From one thread only. */
	fun send(samples: ShortArray): Boolean {
		val now = System.nanoTime()
		if (micFirstNanos == 0L) {
			micFirstNanos = now
		} else {
			longestMicGapMillis = maxOf(longestMicGapMillis, (now - micLastNanos) / 1_000_000)
			micSamples += samples.size
		}
		micLastNanos = now
		val to = remote ?: return false
		val type = payloadType ?: return false
		val size = packetSamples
		val step = timestampStep
		if (size == 0 || step == 0L) return false
		pending += samples
		while (pending.size >= size) {
			val payload = encodePcm(pending.copyOfRange(0, size), format.littleEndian)
			pending = pending.copyOfRange(size, pending.size)
			val packet = rtpHeader(type, sequenceOut, timestampOut, ssrc) + payload
			socket.send(DatagramPacket(packet, packet.size, to))
			sequenceOut = (sequenceOut + 1) and 0xFFFF
			timestampOut = (timestampOut + step) and 0xFFFFFFFFL
			sent++
		}
		return true
	}

	private companion object {
		const val MAX_PACKET = 2048
		const val MIN_AUDIO_BYTES = 40
		const val STEPS_TO_LEARN = 9
		val DYNAMIC_TYPES = 96..127
	}
}

private fun rtpHeader(
	payloadType: Int,
	sequence: Int,
	timestamp: Long,
	ssrc: Int,
): ByteArray =
	java.nio.ByteBuffer
		.allocate(12)
		.put(0x80.toByte())
		.put(payloadType.toByte())
		.putShort(sequence.toShort())
		.putInt(timestamp.toInt())
		.putInt(ssrc)
		.array()
