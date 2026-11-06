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

class RingerTest {
	private var plays = 0
	private var stops = 0
	private val ringer =
		Ringer {
			plays++
			Ringer.Sound { stops++ }
		}

	/** HA's doorbell and the SIP call that follows it both ask to ring: it used to sound twice. */
	@Test
	fun `it rings once however many ask`() {
		ringer.start()
		ringer.start()
		assertEquals(1, plays)
		assertTrue(ringer.isRinging)
	}

	@Test
	fun `stopping silences it, and it can ring again`() {
		ringer.start()
		ringer.stop()
		ringer.stop()
		assertEquals(1, stops)
		assertFalse(ringer.isRinging)
		ringer.start()
		assertEquals(2, plays)
	}

	@Test
	fun `no ringtone on the phone is not ringing`() {
		val silent = Ringer { null }
		silent.start()
		assertFalse(silent.isRinging)
	}
}

class UpdateOutcomeTest {
	/** Asking on the screen put the dialog behind the lock screen, and the panel showed that instead of itself. */
	@Test
	fun `only a silent install counts`() {
		assertEquals(UpdateOutcome.Installed, updateOutcome(android.content.pm.PackageInstaller.STATUS_SUCCESS))
		assertEquals(UpdateOutcome.NeedsSomeone, updateOutcome(android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION))
		assertEquals(UpdateOutcome.Failed, updateOutcome(android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED))
	}
}

class PanelUpdaterTest {
	private val hello = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

	@Test
	fun `the copy says whether it is the file it should be`() {
		val output = java.io.ByteArrayOutputStream()
		assertTrue(copyVerifying("hello".byteInputStream(), output, hello))
		assertEquals("hello", output.toString())
		assertTrue(copyVerifying("hello".byteInputStream(), java.io.ByteArrayOutputStream(), hello.uppercase()))
		assertFalse(copyVerifying("other".byteInputStream(), java.io.ByteArrayOutputStream(), hello))
	}

	@Test
	fun `updates only come over HTTPS`() {
		assertTrue(isUpdateUrl("https://github.com/xoadev/marc2d/releases/download/v1.0.0/marc2d.apk"))
		assertFalse(isUpdateUrl("http://192.0.2.1/marc2d.apk"))
		assertFalse(isUpdateUrl("file:///sdcard/marc2d.apk"))
	}
}

class SignalReportsTest {
	@Test
	fun `only a real change of Wi-Fi signal is reported`() {
		val reports = SignalReports()
		assertTrue(reports.shouldReport(-60, enabled = true))
		assertFalse(reports.shouldReport(-62, enabled = true))
		assertFalse(reports.shouldReport(-64, enabled = true))
		assertTrue(reports.shouldReport(-65, enabled = true))
	}

	@Test
	fun `it reports the signal again when the sensors come back`() {
		val reports = SignalReports()
		assertTrue(reports.shouldReport(-60, enabled = true))
		assertFalse(reports.shouldReport(-60, enabled = false))
		assertTrue(reports.shouldReport(-60, enabled = true))
	}
}

class LightPresenceTest {
	private fun presence(start: Float = 100f) = LightPresence().apply { update(start, START) }

	@Test
	fun `the first reading is not someone arriving`() {
		assertEquals(0, presence().remaining(START))
	}

	@Test
	fun `a sudden change is someone, for a minute`() {
		val presence = presence()
		presence.update(60f, START + 1000)
		assertEquals(60_000, presence.remaining(START + 1000))
		assertEquals(10_000, presence.remaining(START + 51_000))
		assertEquals(0, presence.remaining(START + 61_000))
	}

	@Test
	fun `each new change extends it`() {
		val presence = presence()
		presence.update(60f, START + 1000)
		presence.update(100f, START + 31_000)
		assertEquals(60_000, presence.remaining(START + 31_000))
	}

	@Test
	fun `the sensor's small fluctuations are nobody`() {
		val presence = presence()
		presence.update(110f, START + 1000)
		presence.update(95f, START + 2000)
		assertEquals(0, presence.remaining(START + 2000))
	}

	@Test
	fun `the sun moving slowly is nobody`() {
		val presence = presence()
		var lux = 100f
		repeat(120) { minute ->
			lux *= 1.02f
			presence.update(lux, START + (minute + 1) * 60_000L)
		}
		assertEquals(0, presence.remaining(START + 120 * 60_000L))
	}

	@Test
	fun `changes in the dark are too small to tell`() {
		val presence = presence(1f)
		presence.update(2.5f, START + 1000)
		assertEquals(0, presence.remaining(START + 1000))
	}

	@Test
	fun `its own screen changing is nobody, and what it left is the new normal`() {
		val presence = presence()
		presence.ignoreChanges(START + 1000)
		presence.update(300f, START + 2000)
		assertEquals(0, presence.remaining(START + 2000))
		presence.update(310f, START + 8000)
		assertEquals(0, presence.remaining(START + 8000))
		presence.update(100f, START + 9000)
		assertEquals(60_000, presence.remaining(START + 9000))
	}

	private companion object {
		const val START = 1_000_000L
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
	fun `the camera being on is always seen, except during an alarm`() {
		assertEquals(DroidMood.Recording, DroidMoods.resolve(HaMood.Normal, hasProblem = true, dark = true, listening = true, cameraOn = true))
		assertEquals(DroidMood.Intruder, DroidMoods.resolve(HaMood.Intruder, hasProblem = false, dark = false, cameraOn = true))
		assertEquals(DroidMood.Alarm, DroidMoods.resolve(HaMood.Alarm, hasProblem = false, dark = false, cameraOn = true))
	}

	@Test
	fun `sound has its own eye, but only with the camera on`() {
		assertEquals(DroidMood.RecordingSound, DroidMoods.resolve(HaMood.Normal, hasProblem = false, dark = false, cameraOn = true, cameraSound = true))
		assertEquals(DroidMood.Walking, DroidMoods.resolve(HaMood.Normal, hasProblem = false, dark = false, cameraSound = true))
		assertEquals(DroidMood.Alarm, DroidMoods.resolve(HaMood.Alarm, hasProblem = false, dark = false, cameraOn = true, cameraSound = true))
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
