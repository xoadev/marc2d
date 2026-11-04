package dev.xoa.marc2d

import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PanelReadingsTest {
	@Test
	fun `does not publish small light changes`() {
		assertTrue(PanelReadings.isSignificant(null, 1f))
		assertFalse(PanelReadings.isSignificant(100f, 110f))
		assertTrue(PanelReadings.isSignificant(100f, 130f))
		assertFalse(PanelReadings.isSignificant(1f, 2f))
	}

	@Test
	fun `reports the light again when the sensors come back`() {
		val reports = LightReports()
		assertTrue(reports.shouldReport(100f, enabled = true))
		assertFalse(reports.shouldReport(101f, enabled = true))
		assertFalse(reports.shouldReport(101f, enabled = false))
		assertTrue(reports.shouldReport(101f, enabled = true))
	}
}

class VoiceFailuresTest {
	@Test
	fun `a microphone error ends the listening instead of repeating`() {
		assertEquals(3_200, microphoneBytes(3_200))
		listOf(0, -3, -6).forEach { read -> assertFailsWith<IOException> { microphoneBytes(read) } }
	}

	@Test
	fun `gives up on a server that stops answering`() {
		val target = File.createTempFile("model", ".zip")
		try {
			ServerSocket(0).use { silent ->
				assertFailsWith<SocketTimeoutException> {
					VoiceModel.download("http://127.0.0.1:${silent.localPort}/model.zip", target, timeoutMillis = 100)
				}
			}
		} finally {
			target.delete()
		}
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
