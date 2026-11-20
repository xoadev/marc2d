package dev.xoa.marc2d

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RtpTest {
	@Test
	fun `L16 is big-endian, both ways`() {
		val bytes = byteArrayOf(0x7F, 0xF3.toByte(), 0xFF.toByte(), 0x10, 0x00)
		assertContentEquals(shortArrayOf(-3073, 4096), decodePcm(bytes, 1, 4))
		val samples = shortArrayOf(-13, 16, Short.MAX_VALUE, Short.MIN_VALUE)
		assertContentEquals(samples, decodePcm(encodePcm(samples), 0, 8))
		assertContentEquals(bytes.copyOfRange(1, 5), encodePcm(shortArrayOf(-3073, 4096)))
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

	private fun rtp(
		type: Int,
		sequence: Int,
		timestamp: Long,
		payload: ByteArray,
	): ByteArray = rtpHeader(type, sequence, timestamp, ssrc = 1) + payload
}
