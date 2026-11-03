package dev.xoa.marc2d

import java.time.LocalDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ClockFormatTest {
	private val now = LocalDateTime.of(2026, 11, 2, 7, 5, 30)

	@Test
	fun `the time uses 24 hours with leading zeros`() {
		assertEquals("07:05", ClockFormat.time(now))
	}

	@Test
	fun `the date has weekday and month and starts with a capital`() {
		assertEquals("Monday, November 2", ClockFormat.date(now, Locale.forLanguageTag("en-US"), "EEEE, MMMM d"))
	}

	@Test
	fun `the time moves every minute without drifting away`() {
		assertNotEquals(ClockFormat.burnInOffset(now), ClockFormat.burnInOffset(now.plusMinutes(1)))
		(0..59).map { ClockFormat.burnInOffset(now.withMinute(it)) }.forEach { (x, y) ->
			assertTrue(x in -8..8 && y in -8..8)
		}
	}

	@Test
	fun `computes the time until the next minute`() {
		assertEquals(30_000L, ClockFormat.millisToNextMinute(now))
	}
}
