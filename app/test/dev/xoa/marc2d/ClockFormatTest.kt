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
	fun `la hora va en 24 horas con ceros`() {
		assertEquals("07:05", ClockFormat.time(now))
	}

	@Test
	fun `la fecha lleva dia y mes y empieza en mayuscula`() {
		assertEquals("Lunes, 2 de noviembre", ClockFormat.date(now, Locale.forLanguageTag("es-ES")))
	}

	@Test
	fun `la hora cambia de sitio cada minuto sin alejarse`() {
		assertNotEquals(ClockFormat.burnInOffset(now), ClockFormat.burnInOffset(now.plusMinutes(1)))
		(0..59).map { ClockFormat.burnInOffset(now.withMinute(it)) }.forEach { (x, y) ->
			assertTrue(x in -8..8 && y in -8..8)
		}
	}

	@Test
	fun `calcula el tiempo hasta el siguiente minuto`() {
		assertEquals(30_000L, ClockFormat.millisToNextMinute(now))
	}
}
