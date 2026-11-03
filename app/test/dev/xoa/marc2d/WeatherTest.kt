package dev.xoa.marc2d

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {
	private val now = Instant.parse("2026-11-02T17:05:00Z")

	private val state =
		HaProtocol.parse(
			"""
			{"id":2,"type":"event","event":{"a":{"weather.casa":{"s":"sunny","a":{"temperature":17.3,"humidity":63}}}}}
			""".trimIndent(),
		)

	private val forecast =
		HaProtocol.parse(
			"""
			{"id":3,"type":"result","success":true,"result":{"response":{"weather.casa":{"forecast":[
			  {"condition":"partlycloudy","datetime":"2026-11-02T17:00:00+00:00","temperature":15},
			  {"condition":"clear-night","datetime":"2026-11-02T18:00:00+00:00","temperature":12.9}
			]}}}}
			""".trimIndent(),
		)

	@Test
	fun `lee el estado y la prevision de Home Assistant`() {
		val report = assertNotNull(HaProtocol.weather("weather.casa", state, forecast, now))

		assertEquals("sunny", report.condition)
		assertEquals(17.3, report.temperature)
		assertEquals(2, report.forecast.size)
	}

	@Test
	fun `una entidad que no existe no da informe`() {
		assertNull(HaProtocol.weather("weather.otra", state, forecast, now))
	}

	@Test
	fun `una prevision vacia no impide mostrar el tiempo actual`() {
		val empty = HaProtocol.parse("""{"id":3,"type":"result","success":false,"error":{"code":"x"}}""")

		val report = assertNotNull(HaProtocol.weather("weather.casa", state, empty, now))

		assertTrue(report.forecast.isEmpty())
	}

	@Test
	fun `la prevision son las proximas 8 horas sin la que ya ha empezado`() {
		val hours =
			(0L..12L).map {
				HourForecast(Instant.parse("2026-11-02T17:00:00Z").plus(Duration.ofHours(it)), "sunny", 10.0)
			}
		val upcoming = WeatherReport("sunny", 10.0, hours, now).upcoming(now)

		assertEquals(8, upcoming.size)
		assertEquals(Instant.parse("2026-11-02T18:00:00Z"), upcoming.first().time)
	}

	@Test
	fun `un informe de hace mas de una hora deja de mostrarse`() {
		val report = WeatherReport("sunny", 10.0, emptyList(), now)

		assertTrue(report.isFresh(now.plus(Duration.ofMinutes(59))))
		assertFalse(report.isFresh(now.plus(Duration.ofMinutes(61))))
	}

	@Test
	fun `presenta temperatura y hora locales`() {
		assertEquals("13°", WeatherFormat.temperature(12.9))
		assertEquals("19", WeatherFormat.hour(Instant.parse("2026-11-02T18:00:00Z"), ZoneId.of("Europe/Madrid")))
	}
}
