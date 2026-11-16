package dev.xoa.marc2d

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntityReadingsTest {
	/** As `subscribe_entities` sends it, with the attributes of an electricity price in the Nord Pool format. */
	private val event =
		HaProtocol.parse(
			"""
			{"id":5,"type":"event","event":{"a":{
			  "sensor.car_battery":{"s":"73.0","a":{"unit_of_measurement":"%"}},
			  "sensor.outside":{"s":"unavailable","a":{}},
			  "sensor.door":{"s":"open","a":{}},
			  "sensor.price":{"s":"0.19","a":{"unit_of_measurement":"EUR/kWh",
			    "raw_today":[
			      {"start":"2026-11-15T22:00:00+01:00","value":0.20953},
			      {"start":"2026-11-15T23:00:00+01:00","value":0.19227}],
			    "raw_tomorrow":[
			      {"start":"2026-11-16T00:00:00+01:00","value":0.16925},
			      {"start":"2026-11-16T01:00:00+01:00","value":0.14867},
			      {"start":"not a time","value":1}]}}
			}}}
			""".trimIndent(),
		)

	@Test
	fun `readings take the entity's value and unit, and warn only past their limits`() {
		val readings =
			EntityReadings.readings(
				listOf(
					ReadingConfig("sensor.car_battery", "Car", warnBelow = 80.0),
					ReadingConfig("sensor.outside", "Outside"),
					ReadingConfig("sensor.door", "Door", warnAbove = 1.0),
					ReadingConfig("sensor.missing", "Gone"),
				),
				event,
			)
		assertEquals(
			listOf(Reading("Car", "73", "%", warning = true, level = 0.73f), Reading("Door", "open", null, warning = false)),
			readings,
		)
		assertEquals(false, EntityReadings.readings(listOf(ReadingConfig("sensor.car_battery", "Car", warnBelow = 20.0)), event).single().warning)
	}

	@Test
	fun `a percentage gives a level, and the icon comes from the configuration`() {
		val car = EntityReadings.readings(listOf(ReadingConfig("sensor.car_battery", "Car", icon = "car")), event).single()
		assertEquals(0.73f, car.level)
		assertEquals(ReadingIcon.Car, ReadingIcon.parse(car.icon))
		assertNull(ReadingIcon.parse("from a future version"))
		assertEquals("%", unitSuffix("%"))
		assertEquals(" kWh", unitSuffix("kWh"))
	}

	@Test
	fun `the battery fills with its level`() {
		val empty = batteryRows(0f)
		val full = batteryRows(1f)
		assertTrue(empty.all { it.length == 12 } && full.all { it.length == 12 })
		assertEquals(0, empty[5].substring(2, 8).count { it == '#' })
		assertEquals(6, full[5].substring(2, 8).count { it == '#' })
		assertEquals(4, batteryRows(0.7f)[5].substring(2, 8).count { it == '#' })
	}

	@Test
	fun `numbers lose the decimals they do not need`() {
		assertEquals("73", EntityReadings.format(73.0))
		assertEquals("-105.6", EntityReadings.format(-105.6))
		assertEquals("0.19", EntityReadings.format(0.19227))
	}

	@Test
	fun `a chart joins today and tomorrow, in order`() {
		val chart = EntityReadings.chart(ChartConfig("sensor.price", "Electricity"), event)!!
		assertEquals("EUR/kWh", chart.unit)
		assertEquals(4, chart.hours.size)
		assertEquals(Instant.parse("2026-11-15T21:00:00Z"), chart.hours.first().start)
		assertNull(EntityReadings.chart(ChartConfig("sensor.car_battery", "Car"), event), "no values by the hour")
	}

	@Test
	fun `the window starts at the value running now, and the lowest are picked out`() {
		val hours = EntityReadings.chart(ChartConfig("sensor.price", "Electricity"), event)!!.hours
		// Half past eleven: the 23:00 price is still running.
		val window = EntityReadings.window(hours, Instant.parse("2026-11-15T22:30:00Z"))
		assertEquals(3, window.size)
		assertEquals(0.19227, window.first().value)
		assertEquals(setOf(window.last().start), EntityReadings.cheapest(window, span = 3600))
	}

	@Test
	fun `quarters of an hour count as such`() {
		val start = Instant.parse("2026-11-16T00:00:00Z")
		val quarters = List(8) { HourValue(start.plusSeconds(900L * it), it.toDouble()) }
		assertEquals(900, EntityReadings.step(quarters))
		assertEquals(4, EntityReadings.cheapest(quarters, span = 3600).size, "an hour is four quarters")
		assertEquals(4, EntityReadings.window(quarters, start.plusSeconds(1800), span = 3600).size)
	}

	@Test
	fun `the configuration brings readings and charts`() {
		val config =
			PanelConfig.fromStored(
				"""{"readings":[{"entity":"sensor.car_battery","name":"Car","warn_below":20}],"charts":[{"entity":"sensor.price","name":"Electricity"}]}""",
			)!!
		assertEquals(listOf(ReadingConfig("sensor.car_battery", "Car", warnBelow = 20.0)), config.readings)
		assertEquals(listOf(ChartConfig("sensor.price", "Electricity")), config.charts)
	}
}
