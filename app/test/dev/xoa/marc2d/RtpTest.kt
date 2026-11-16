package dev.xoa.marc2d

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RtpTest {
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
