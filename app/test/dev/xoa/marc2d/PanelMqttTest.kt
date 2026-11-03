package dev.xoa.marc2d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel

class PanelMqttTest {
	@Test
	fun `normalizes the panel name for the topics`() {
		assertEquals("upper_kitchen", PanelTopics("Upper Kitchen").id)
		assertEquals("panel", PanelTopics("!!!").id)
	}

	@Test
	fun `discovery includes availability and siren`() {
		val topics = PanelTopics("Lounge")
		val payload = PanelDiscovery.payload(topics, "Lounge", "Pixel 3a")
		assertTrue(topics.availability in payload)
		assertTrue(topics.sirenCommand in payload)
	}

	@Test
	fun `does not publish small light changes`() {
		assertTrue(PanelReadings.isSignificant(null, 1f))
		assertFalse(PanelReadings.isSignificant(100f, 110f))
		assertTrue(PanelReadings.isSignificant(100f, 130f))
		assertFalse(PanelReadings.isSignificant(1f, 2f))
	}

	@Test
	fun `does not show the MQTT password`() {
		assertFalse("s3cr3t" in MqttConfig("192.0.2.8", username = "u", password = "s3cr3t").toString())
	}
}

class MqttCodecTest {
	@Test
	fun `encodes a multi-byte length`() {
		val packet = MqttCodec.publish("t", "x".repeat(200), retain = true)
		val read = MqttCodec.read(packet.inputStream())
		val publish = MqttCodec.parsePublish(read)
		assertEquals(MqttCodec.PUBLISH, read.type)
		assertEquals(1, read.flags)
		assertEquals("t", publish.topic)
		assertEquals(200, publish.payload.length)
		assertEquals(null, publish.packetId)
	}

	@Test
	fun `reads the identifier of a QoS 1 message`() {
		val body = byteArrayOf(0, 1, 't'.code.toByte(), 0, 7, 'O'.code.toByte(), 'N'.code.toByte())
		val publish = MqttCodec.parsePublish(MqttPacket(MqttCodec.PUBLISH, 2, body))
		assertEquals(7, publish.packetId)
		assertEquals("ON", publish.payload)
	}

	@Test
	fun `a truncated packet is not accepted`() {
		val packet = MqttCodec.pingreq().copyOf(1)
		assertTrue(runCatching { MqttCodec.read(packet.inputStream()) }.isFailure)
	}
}

class PanelMqttClientTest {
	private class FakeSession : MqttSession {
		val published = mutableListOf<Pair<String, String>>()
		val subscribed = mutableListOf<String>()

		override fun publish(
			topic: String,
			payload: String,
			retain: Boolean,
		) {
			published += topic to payload
		}

		override fun subscribe(topic: String) {
			subscribed += topic
		}
	}

	private val config = MqttConfig("192.0.2.8", username = "u", password = "p")
	private val topics = PanelTopics("Lounge")

	@Test
	fun `on connecting it announces itself and sends what was read earlier`() {
		val session = FakeSession()
		val sirens = mutableListOf<Boolean>()
		val doorbells = mutableListOf<Pair<String, String>>()
		val moods = mutableListOf<String>()
		var message: ((String, String) -> Unit)? = null
		val scope = CoroutineScope(Dispatchers.Unconfined)
		val mqtt =
			PanelMqtt(
				scope,
				"Pixel",
				{ sirens += it },
				{ intercom, payload -> doorbells += intercom to payload },
				{ moods += it },
				{ _, onConnected, onMessage ->
					onConnected(session)
					message = onMessage
					awaitCancellation()
				},
			)
		mqtt.publish(PanelTopics::battery, "80")

		mqtt.apply(config, "Lounge")

		assertEquals(MqttStatus.Connected, mqtt.status.value)
		assertEquals(listOf(topics.sirenCommand, topics.moodCommand, "marc2d/doorbell/+"), session.subscribed)
		assertTrue(topics.discovery to session.published[0].second in session.published)
		assertTrue(topics.availability to PanelTopics.ONLINE in session.published)
		assertTrue(topics.battery to "80" in session.published)

		mqtt.publish(PanelTopics::battery, "80")
		assertEquals(1, session.published.count { it.first == topics.battery })

		message?.invoke(topics.sirenCommand, PanelTopics.ON)
		message?.invoke("other/topic", PanelTopics.OFF)
		assertEquals(listOf(true), sirens)

		message?.invoke(topics.moodCommand, "Alarm")
		assertEquals(listOf("Alarm"), moods)
		message?.invoke("marc2d/doorbell/parking", PanelTopics.RING)
		assertEquals(listOf("parking" to PanelTopics.RING), doorbells)
		mqtt.doorbellAnswered("parking")
		assertTrue("marc2d/doorbell/parking" to PanelTopics.ANSWERED in session.published)
		scope.cancel()
	}
}

class DroidMoodTest {
	@Test
	fun `what HA commands takes priority`() {
		assertEquals(DroidMood.Intruder, DroidMoods.resolve(HaMood.Intruder, hasProblem = true, dark = true))
		assertEquals(DroidMood.Alarm, DroidMoods.resolve(HaMood.Alarm, hasProblem = true, dark = false))
	}

	@Test
	fun `the panel works out the other states by itself`() {
		assertEquals(DroidMood.Offline, DroidMoods.resolve(HaMood.Normal, hasProblem = true, dark = true))
		assertEquals(DroidMood.Sleeping, DroidMoods.resolve(HaMood.Normal, hasProblem = false, dark = true))
		assertEquals(DroidMood.Walking, DroidMoods.resolve(HaMood.Normal, hasProblem = false, dark = false))
	}

	@Test
	fun `ignores an unknown state`() {
		assertEquals(null, HaMood.fromLabel("Party"))
	}
}

class MjpegParserTest {
	private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

	@Test
	fun `extracts images even if they arrive split`() {
		val frames = mutableListOf<List<Byte>>()
		val parser = MjpegParser()
		val first = bytes(0x2D, 0xFF, 0xD8, 0x01)
		val second = bytes(0x02, 0xFF, 0xD9, 0x2D, 0xFF, 0xD8, 0xFF, 0xD9)
		parser.feed(first, first.size) { frames += it.toList() }
		parser.feed(second, second.size) { frames += it.toList() }
		assertEquals(2, frames.size)
		assertEquals(bytes(0xFF, 0xD8, 0x01, 0x02, 0xFF, 0xD9).toList(), frames[0])
		assertEquals(bytes(0xFF, 0xD8, 0xFF, 0xD9).toList(), frames[1])
	}
}
