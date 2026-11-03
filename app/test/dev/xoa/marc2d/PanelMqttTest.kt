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
	fun `normaliza el nombre del panel para los temas`() {
		assertEquals("cocina_baja", PanelTopics("Cocina Baja").id)
		assertEquals("panel", PanelTopics("¡¡¡").id)
	}

	@Test
	fun `el descubrimiento incluye disponibilidad y sirena`() {
		val topics = PanelTopics("Salon")
		val payload = PanelDiscovery.payload(topics, "Salon", "Pixel 3a")
		assertTrue(topics.availability in payload)
		assertTrue(topics.sirenCommand in payload)
	}

	@Test
	fun `no publica cambios de luz pequeños`() {
		assertTrue(PanelReadings.isSignificant(null, 1f))
		assertFalse(PanelReadings.isSignificant(100f, 110f))
		assertTrue(PanelReadings.isSignificant(100f, 130f))
		assertFalse(PanelReadings.isSignificant(1f, 2f))
	}

	@Test
	fun `no muestra la contraseña de MQTT`() {
		assertFalse("s3cr3t" in MqttConfig("192.0.2.8", username = "u", password = "s3cr3t").toString())
	}
}

class MqttCodecTest {
	@Test
	fun `codifica una longitud de varios bytes`() {
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
	fun `lee el identificador de un mensaje con QoS 1`() {
		val body = byteArrayOf(0, 1, 't'.code.toByte(), 0, 7, 'O'.code.toByte(), 'N'.code.toByte())
		val publish = MqttCodec.parsePublish(MqttPacket(MqttCodec.PUBLISH, 2, body))
		assertEquals(7, publish.packetId)
		assertEquals("ON", publish.payload)
	}

	@Test
	fun `un paquete cortado no se da por bueno`() {
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
	private val topics = PanelTopics("Salon")

	@Test
	fun `al conectar se anuncia y envia lo leido antes`() {
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

		mqtt.apply(config, "Salon")

		assertEquals(MqttStatus.Connected, mqtt.status.value)
		assertEquals(listOf(topics.sirenCommand, topics.moodCommand, "marc2d/doorbell/+"), session.subscribed)
		assertTrue(topics.discovery to session.published[0].second in session.published)
		assertTrue(topics.availability to PanelTopics.ONLINE in session.published)
		assertTrue(topics.battery to "80" in session.published)

		mqtt.publish(PanelTopics::battery, "80")
		assertEquals(1, session.published.count { it.first == topics.battery })

		message?.invoke(topics.sirenCommand, PanelTopics.ON)
		message?.invoke("otro/tema", PanelTopics.OFF)
		assertEquals(listOf(true), sirens)

		message?.invoke(topics.moodCommand, "Alarma")
		assertEquals(listOf("Alarma"), moods)
		message?.invoke("marc2d/doorbell/parking", PanelTopics.RING)
		assertEquals(listOf("parking" to PanelTopics.RING), doorbells)
		mqtt.doorbellAnswered("parking")
		assertTrue("marc2d/doorbell/parking" to PanelTopics.ANSWERED in session.published)
		scope.cancel()
	}
}

class DroidMoodTest {
	@Test
	fun `lo que manda HA tiene prioridad`() {
		assertEquals(DroidMood.Intruder, DroidMoods.resolve(HaMood.Intruder, hasProblem = true, dark = true))
		assertEquals(DroidMood.Alarm, DroidMoods.resolve(HaMood.Alarm, hasProblem = true, dark = false))
	}

	@Test
	fun `el panel deduce solo los demas estados`() {
		assertEquals(DroidMood.Offline, DroidMoods.resolve(HaMood.Normal, hasProblem = true, dark = true))
		assertEquals(DroidMood.Sleeping, DroidMoods.resolve(HaMood.Normal, hasProblem = false, dark = true))
		assertEquals(DroidMood.Walking, DroidMoods.resolve(HaMood.Normal, hasProblem = false, dark = false))
	}

	@Test
	fun `ignora un estado desconocido`() {
		assertEquals(null, HaMood.fromLabel("Fiesta"))
	}
}

class MjpegParserTest {
	private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

	@Test
	fun `saca imagenes aunque lleguen partidas`() {
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
