package dev.xoa.marc2d

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

data class MqttPacket(
	val type: Int,
	val flags: Int,
	val body: ByteArray,
)

data class MqttPublish(
	val topic: String,
	val payload: String,
	val packetId: Int?,
)

/** The MQTT 3.1.1 packets the panel uses. It publishes with QoS 0: TCP is enough on the LAN. */
object MqttCodec {
	const val CONNACK = 2
	const val PUBLISH = 3
	const val PINGRESP = 13

	private const val CONNECT = 1
	private const val PUBACK = 4
	private const val SUBSCRIBE = 8
	private const val PINGREQ = 12
	private const val PROTOCOL_LEVEL = 4
	private const val FLAG_CLEAN_SESSION = 0x02
	private const val FLAG_WILL = 0x04
	private const val FLAG_WILL_QOS1 = 0x08
	private const val FLAG_WILL_RETAIN = 0x20
	private const val FLAG_PASSWORD = 0x40
	private const val FLAG_USERNAME = 0x80
	private const val FLAG_RETAIN = 0x01
	private const val FLAGS_SUBSCRIBE = 0x02
	private const val QOS_MASK = 0x06
	private const val LENGTH_CONTINUES = 0x80
	private const val LENGTH_BITS = 7
	private const val LENGTH_MASK = 0x7F
	private const val MAX_LENGTH_BYTES = 4
	private const val BYTE_MASK = 0xFF
	private const val BYTE_BITS = 8
	private const val TYPE_SHIFT = 4
	private const val FLAGS_MASK = 0x0F

	fun connect(
		clientId: String,
		username: String,
		password: String,
		keepAliveSeconds: Int,
		will: MqttWill,
	): ByteArray {
		val flags =
			FLAG_CLEAN_SESSION or FLAG_WILL or FLAG_WILL_QOS1 or FLAG_WILL_RETAIN or FLAG_PASSWORD or FLAG_USERNAME
		return packet(CONNECT, 0) {
			text("MQTT")
			write(PROTOCOL_LEVEL)
			write(flags)
			short(keepAliveSeconds)
			text(clientId)
			text(will.topic)
			text(will.payload)
			text(username)
			text(password)
		}
	}

	fun publish(
		topic: String,
		payload: String,
		retain: Boolean,
	): ByteArray =
		packet(PUBLISH, if (retain) FLAG_RETAIN else 0) {
			text(topic)
			write(payload.toByteArray())
		}

	fun subscribe(
		packetId: Int,
		topic: String,
	): ByteArray =
		packet(SUBSCRIBE, FLAGS_SUBSCRIBE) {
			short(packetId)
			text(topic)
			write(1)
		}

	fun puback(packetId: Int): ByteArray = packet(PUBACK, 0) { short(packetId) }

	fun pingreq(): ByteArray = packet(PINGREQ, 0) {}

	fun read(input: InputStream): MqttPacket {
		val first = input.read()
		if (first < 0) throw EOFException()
		var length = 0
		var count = 0
		while (true) {
			val byte = input.read()
			if (byte < 0) throw EOFException()
			length = length or ((byte and LENGTH_MASK) shl (LENGTH_BITS * count))
			count++
			if (byte and LENGTH_CONTINUES == 0) break
			if (count == MAX_LENGTH_BYTES) throw IllegalStateException("Invalid MQTT length")
		}
		return MqttPacket(first shr TYPE_SHIFT, first and FLAGS_MASK, input.readNBytes(length).also { if (it.size < length) throw EOFException() })
	}

	/** The return code of a CONNACK: 0 means accepted. */
	fun connackCode(packet: MqttPacket): Int = packet.body[1].toInt()

	fun parsePublish(packet: MqttPacket): MqttPublish {
		val body = packet.body
		val topicLength = short(body, 0)
		val topicEnd = 2 + topicLength
		val hasId = packet.flags and QOS_MASK != 0
		return MqttPublish(
			topic = String(body, 2, topicLength),
			payload = String(body, if (hasId) topicEnd + 2 else topicEnd, body.size - topicEnd - if (hasId) 2 else 0),
			packetId = if (hasId) short(body, topicEnd) else null,
		)
	}

	private fun short(
		bytes: ByteArray,
		at: Int,
	): Int = ((bytes[at].toInt() and BYTE_MASK) shl BYTE_BITS) or (bytes[at + 1].toInt() and BYTE_MASK)

	private fun ByteArrayOutputStream.short(value: Int) {
		write(value shr BYTE_BITS)
		write(value and BYTE_MASK)
	}

	private fun ByteArrayOutputStream.text(value: String) {
		val bytes = value.toByteArray()
		short(bytes.size)
		write(bytes)
	}

	private fun packet(
		type: Int,
		flags: Int,
		body: ByteArrayOutputStream.() -> Unit,
	): ByteArray {
		val content = ByteArrayOutputStream().apply(body).toByteArray()
		val out = ByteArrayOutputStream()
		out.write((type shl TYPE_SHIFT) or flags)
		var remaining = content.size
		do {
			val digit = remaining and LENGTH_MASK
			remaining = remaining shr LENGTH_BITS
			out.write(if (remaining > 0) digit or LENGTH_CONTINUES else digit)
		} while (remaining > 0)
		out.write(content)
		return out.toByteArray()
	}
}
