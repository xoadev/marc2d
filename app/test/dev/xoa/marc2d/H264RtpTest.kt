package dev.xoa.marc2d

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class H264RtpTest {
	private fun frame(vararg units: ByteArray) = H264Frame(units.fold(ByteArray(0)) { all, unit -> all + byteArrayOf(0, 0, 0, 1) + unit }, 1_000_000, true)

	@Test
	fun `splits both kinds of start code`() {
		val data = byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2)
		val units = nalUnits(data)
		assertEquals(2, units.size)
		assertContentEquals(byteArrayOf(0x67, 1), units[0])
		assertContentEquals(byteArrayOf(0x68, 2), units[1])
	}

	@Test
	fun `finds the parameters in the encoder's configuration`() {
		val parameters = h264Parameters(byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2))!!
		assertContentEquals(byteArrayOf(0x67, 1), parameters.sps)
		assertContentEquals(byteArrayOf(0x68, 2), parameters.pps)
		assertNull(h264Parameters(byteArrayOf(0, 0, 0, 1, 0x67, 1)))
	}

	@Test
	fun `a small unit goes whole, marked as the end of the frame`() {
		val packets = H264Packetizer(ssrc = 7).packets(frame(byteArrayOf(0x65, 1, 2)))
		assertEquals(1, packets.size)
		val packet = packets[0]
		assertEquals(0x80, packet[0].toInt() and 0xFF)
		assertEquals(0x80 or 96, packet[1].toInt() and 0xFF)
		assertContentEquals(byteArrayOf(0x65, 1, 2), packet.copyOfRange(12, packet.size))
	}

	@Test
	fun `the clock runs at 90 kHz`() {
		val packet = H264Packetizer().packets(frame(byteArrayOf(0x65)))[0]
		val timestamp = packet.copyOfRange(4, 8).fold(0L) { all, byte -> (all shl 8) or (byte.toLong() and 0xFF) }
		assertEquals(90_000, timestamp)
	}

	@Test
	fun `only the last unit of a frame carries the marker`() {
		val packets = H264Packetizer().packets(frame(byteArrayOf(0x67, 1), byteArrayOf(0x65, 2)))
		assertEquals(listOf(false, true), packets.map { it[1].toInt() and 0x80 != 0 })
	}

	@Test
	fun `a large unit is fragmented and can be put back together`() {
		val unit = ByteArray(5000) { (it % 251).toByte() }.also { it[0] = 0x65 }
		val packets = H264Packetizer(maxPacket = 1400).packets(frame(unit))
		assertTrue(packets.size > 1)
		assertTrue(packets.all { it.size <= 1400 })
		packets.forEach { assertEquals(28, it[12].toInt() and 0x1F) }
		assertEquals(0x80 or 5, packets.first()[13].toInt() and 0xFF)
		assertEquals(5, packets[1][13].toInt() and 0xFF)
		assertEquals(0x40 or 5, packets.last()[13].toInt() and 0xFF)
		assertFalse(packets.first()[1].toInt() and 0x80 != 0)
		assertTrue(packets.last()[1].toInt() and 0x80 != 0)
		val rebuilt = packets.fold(byteArrayOf(0x65)) { all, packet -> all + packet.copyOfRange(14, packet.size) }
		assertContentEquals(unit, rebuilt)
	}

	@Test
	fun `the sequence number has no gaps and wraps around`() {
		val packets = H264Packetizer().packets(frame(*Array(70000) { byteArrayOf(0x41) }))
		val numbers = packets.map { ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF) }
		numbers.zipWithNext().forEach { (a, b) -> assertEquals((a + 1) and 0xFFFF, b) }
	}
}
