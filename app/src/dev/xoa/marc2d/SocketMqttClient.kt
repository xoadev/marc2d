package dev.xoa.marc2d

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** MQTT 3.1.1 client over a `Socket`, without TLS: the panel only talks to a broker on the LAN. */
class SocketMqttClient : MqttClient {
	override suspend fun run(
		options: MqttOptions,
		onConnected: (MqttSession) -> Unit,
		onMessage: (topic: String, payload: String) -> Unit,
	): Unit =
		withContext(Dispatchers.IO) {
			Socket().use { socket ->
				socket.connect(InetSocketAddress(options.server, options.port), TIMEOUT_MILLIS)
				socket.soTimeout = TIMEOUT_MILLIS
				val input = BufferedInputStream(socket.getInputStream())
				val output = socket.getOutputStream()
				output.write(MqttCodec.connect(options.clientId, options.username, options.password, KEEP_ALIVE_SECONDS, options.will))
				val ack = MqttCodec.read(input)
				check(ack.type == MqttCodec.CONNACK && MqttCodec.connackCode(ack) == 0) { "The broker rejected the connection" }

				val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
				onConnected(ChannelSession(outgoing))
				coroutineScope {
					launch {
						// Cancelling does not interrupt a blocking read: the socket has to be closed.
						try {
							awaitCancellation()
						} finally {
							socket.close()
						}
					}
					launch {
						closingOnCancel { for (bytes in outgoing) output.write(bytes) }
					}
					launch {
						while (true) {
							delay(KEEP_ALIVE_SECONDS.seconds)
							outgoing.send(MqttCodec.pingreq())
						}
					}
					launch {
						closingOnCancel {
							while (true) {
								val packet = MqttCodec.read(input)
								if (packet.type != MqttCodec.PUBLISH) continue
								val message = MqttCodec.parsePublish(packet)
								message.packetId?.let { outgoing.send(MqttCodec.puback(it)) }
								onMessage(message.topic, message.payload)
							}
						}
					}
				}
			}
		}

	/** Cancelling closes the socket, and its error must not be mistaken for a lost connection. */
	private suspend fun closingOnCancel(block: suspend () -> Unit) {
		try {
			block()
		} catch (e: IOException) {
			currentCoroutineContext().ensureActive()
			throw e
		}
	}

	private class ChannelSession(
		private val outgoing: Channel<ByteArray>,
	) : MqttSession {
		private var nextPacketId = 1

		override fun publish(
			topic: String,
			payload: String,
			retain: Boolean,
		) {
			outgoing.trySend(MqttCodec.publish(topic, payload, retain))
		}

		override fun subscribe(topic: String) {
			outgoing.trySend(MqttCodec.subscribe(nextPacketId++, topic))
		}
	}

	private companion object {
		const val KEEP_ALIVE_SECONDS = 30
		const val TIMEOUT_MILLIS = 45_000
	}
}
