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
			{"id":2,"type":"event","event":{"a":{"weather.home":{"s":"sunny","a":{"temperature":17.3,"humidity":63}}}}}
			""".trimIndent(),
		)

	private val forecast =
		HaProtocol.parse(
			"""
			{"id":3,"type":"result","success":true,"result":{"response":{"weather.home":{"forecast":[
			  {"condition":"partlycloudy","datetime":"2026-11-02T17:00:00+00:00","temperature":15},
			  {"condition":"clear-night","datetime":"2026-11-02T18:00:00+00:00","temperature":12.9}
			]}}}}
			""".trimIndent(),
		)

	@Test
	fun `reads the state and forecast from Home Assistant`() {
		val report = assertNotNull(HaProtocol.weather("weather.home", state, forecast, now))

		assertEquals("sunny", report.condition)
		assertEquals(17.3, report.temperature)
		assertEquals(2, report.forecast.size)
	}

	@Test
	fun `an entity that does not exist gives no report`() {
		assertNull(HaProtocol.weather("weather.other", state, forecast, now))
	}

	@Test
	fun `an empty forecast does not prevent showing the current weather`() {
		val empty = HaProtocol.parse("""{"id":3,"type":"result","success":false,"error":{"code":"x"}}""")

		val report = assertNotNull(HaProtocol.weather("weather.home", state, empty, now))

		assertTrue(report.forecast.isEmpty())
	}

	@Test
	fun `the forecast is the next 8 hours without the one already started`() {
		val hours =
			(0L..12L).map {
				HourForecast(Instant.parse("2026-11-02T17:00:00Z").plus(Duration.ofHours(it)), "sunny", 10.0)
			}
		val upcoming = WeatherReport("sunny", 10.0, hours, now).upcoming(now)

		assertEquals(8, upcoming.size)
		assertEquals(Instant.parse("2026-11-02T18:00:00Z"), upcoming.first().time)
	}

	@Test
	fun `a report over an hour old stops being shown`() {
		val report = WeatherReport("sunny", 10.0, emptyList(), now)

		assertTrue(report.isFresh(now.plus(Duration.ofMinutes(59))))
		assertFalse(report.isFresh(now.plus(Duration.ofMinutes(61))))
	}

	@Test
	fun `presents local temperature and time`() {
		assertEquals("13°", WeatherFormat.temperature(12.9))
		assertEquals("19", WeatherFormat.hour(Instant.parse("2026-11-02T18:00:00Z"), ZoneId.of("Europe/Madrid")))
	}
}
