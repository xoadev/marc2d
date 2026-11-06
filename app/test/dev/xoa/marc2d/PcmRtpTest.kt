package dev.xoa.marc2d

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PcmRtpTest {
	@Test
	fun `the audio setting names its format, and anything else is the standard one`() {
		assertEquals(PcmFormat(16_000, true), PcmFormat.parse("pcm_le_16000"))
		assertEquals(PcmFormat(8_000, false), PcmFormat.parse("l16_8000"))
		assertNull(PcmFormat.parse("standard"))
		assertNull(PcmFormat.parse("from a future version"))
		assertEquals(320, PcmFormat(16_000, true).frameSamples)
	}

	/** What a Dahua VTO sent in silence: `f3 ff` is -13 read in little-endian, and nonsense in the other order. */
	@Test
	fun `both byte orders`() {
		val dahua = byteArrayOf(0xF3.toByte(), 0xFF.toByte(), 0x10, 0x00)
		assertContentEquals(shortArrayOf(-13, 16), decodePcm(dahua, 0, 4, littleEndian = true))
		assertContentEquals(shortArrayOf(-3073, 4096), decodePcm(dahua, 0, 4, littleEndian = false))
		val samples = shortArrayOf(-13, 16, Short.MAX_VALUE, Short.MIN_VALUE)
		assertContentEquals(samples, decodePcm(encodePcm(samples, true), 0, 8, true))
		assertContentEquals(samples, decodePcm(encodePcm(samples, false), 0, 8, false))
		assertContentEquals(dahua, encodePcm(shortArrayOf(-13, 16), littleEndian = true))
	}

	@Test
	fun `reads the RTP header, with what may extend it`() {
		val plain = rtp(97, 7, 320, ByteArray(40))
		val parsed = RtpPacket.parse(plain, plain.size)!!
		assertEquals(97, parsed.payloadType)
		assertEquals(7, parsed.sequence)
		assertEquals(320, parsed.timestamp)
		assertEquals(12, parsed.payloadOffset)
		assertEquals(40, parsed.payloadLength)

		val extended = plain.copyOf().also { it[0] = (0x80 or 0x10 or 0x01).toByte() }
		val withCsrcAndExtension = extended.copyOfRange(0, 12) + ByteArray(4) + byteArrayOf(0, 0, 0, 1) + ByteArray(4) + ByteArray(40)
		// 12 of header, 4 of one CSRC, 4 of the extension's header and 4 of its one word.
		assertEquals(24, RtpPacket.parse(withCsrcAndExtension, withCsrcAndExtension.size)!!.payloadOffset)

		val padded = (plain + byteArrayOf(0, 0, 3)).also { it[0] = (0x80 or 0x20).toByte() }
		assertEquals(40, RtpPacket.parse(padded, padded.size)!!.payloadLength)

		assertNull(RtpPacket.parse(ByteArray(8), 8))
		assertNull(RtpPacket.parse(ByteArray(20), 20))
	}

	@Test
	fun `the sequence number goes round`() {
		assertTrue(isNewer(8, 7))
		assertTrue(isNewer(0, 0xFFFF))
		assertFalse(isNewer(7, 7))
		assertFalse(isNewer(6, 7))
		assertFalse(isNewer(0xFFFF, 0))
	}

	@Test
	fun `plays the intercom's audio and answers in the same format, where it came from`() {
		val panel = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
		val intercom = DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).apply { soTimeout = 2000 }
		val played = LinkedBlockingQueue<ShortArray>()
		val link = PcmRtpLink(panel, PcmFormat(16_000, true), negotiatedPayloadType = 8) { played.add(it) }
		val receiving = Thread { link.receive() }.apply { start() }
		try {
			assertFalse(link.send(ShortArray(320)), "nowhere to send it before hearing the intercom")
			val to = panel.localSocketAddress

			fun send(bytes: ByteArray) = intercom.send(DatagramPacket(bytes, bytes.size, to))
			// What Linphone negotiated, a DTMF event and a short packet are not the intercom's audio.
			send(rtp(8, 1, 0, ByteArray(160)))
			send(rtp(101, 2, 0, ByteArray(4)))
			send(rtp(97, 3, 0, encodePcm(ShortArray(320) { -13 }, true)))
			val heard = assertNotNull(played.poll(2, TimeUnit.SECONDS))
			assertEquals(320, heard.size)
			assertEquals(-13, heard[0].toInt())
			assertTrue(link.isHeard)
			assertNull(played.poll(200, TimeUnit.MILLISECONDS))

			// A packet older than the last one played is dropped.
			send(rtp(97, 2, 0, ByteArray(640)))
			assertNull(played.poll(200, TimeUnit.MILLISECONDS))

			assertTrue(link.send(ShortArray(320) { 16 }))
			assertTrue(link.send(ShortArray(320) { 16 }))
			val buffer = ByteArray(2048)
			val first = DatagramPacket(buffer.copyOf(), 2048).also { intercom.receive(it) }
			val second = DatagramPacket(buffer.copyOf(), 2048).also { intercom.receive(it) }
			val a = RtpPacket.parse(first.data, first.length)!!
			val b = RtpPacket.parse(second.data, second.length)!!
			assertEquals(97, a.payloadType)
			assertEquals(640, a.payloadLength)
			assertEquals((a.sequence + 1) and 0xFFFF, b.sequence)
			assertEquals((a.timestamp + 320) and 0xFFFFFFFFL, b.timestamp)
			assertEquals(16, decodePcm(first.data, a.payloadOffset, a.payloadLength, true)[0].toInt())
		} finally {
			panel.close()
			intercom.close()
			receiving.join(2000)
		}
	}

	private fun rtp(
		type: Int,
		sequence: Int,
		timestamp: Int,
		payload: ByteArray,
	): ByteArray =
		byteArrayOf(
			0x80.toByte(),
			type.toByte(),
			(sequence shr 8).toByte(),
			sequence.toByte(),
			(timestamp shr 24).toByte(),
			(timestamp shr 16).toByte(),
			(timestamp shr 8).toByte(),
			timestamp.toByte(),
			0,
			0,
			0,
			1,
		) + payload
}
