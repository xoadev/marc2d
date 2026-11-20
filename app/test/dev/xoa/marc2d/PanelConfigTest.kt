package dev.xoa.marc2d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PanelConfigTest {
	private val intercom = IntercomConfig("Parking", "192.0.2.8", "9901#1", "s3cr3t")

	@Test
	fun `escapes the hash of the extension in the URI`() {
		assertEquals("sip:9901%231@192.0.2.8", intercom.sipIdentity)
	}

	@Test
	fun `a stored configuration it cannot read is ignored`() {
		// What an older version stored, such as the flags the entities had, is left behind.
		assertEquals(PanelConfig(weather = "weather.home"), PanelConfig.fromStored("""{"weather":"weather.home","siren":false}"""))
		assertNull(PanelConfig.fromStored("""{"intercoms":"not a list"}"""))
		assertNull(PanelConfig.fromStored("""{"intercoms":[{"name":"missing the rest"}]}"""))
		assertNull(PanelConfig.fromStored("not json"))
	}

	@Test
	fun `the call goes over SIP unless an RTSP URL says otherwise, which stays out of logs`() {
		assertNull(intercom.rtsp)
		val url = "rtsp://admin:s3cr3t@192.0.2.8/cam/realmonitor?channel=1&subtype=1"
		val stored =
			PanelConfig.fromStored("""{"intercoms":[{"name":"P","server":"s","extension":"e","password":"p","rtsp":"$url"}]}""")!!
		assertEquals(url, stored.intercoms.single().rtsp)
		assertFalse("s3cr3t" in stored.toString())
	}

	@Test
	fun `the wake phrase comes from Home Assistant, or is the default`() {
		assertEquals("oye mark", PanelConfig.fromStored("""{"voice":{"phrase":"oye mark"}}""")?.voice?.phrase)
		assertEquals(VoiceConfig.DEFAULT_PHRASE, PanelConfig.fromStored("""{"voice":{}}""")?.voice?.phrase)
	}

	@Test
	fun `the stream's password arrives and stays out of logs`() {
		val config = PanelConfig.fromStored("""{"rtsp":{"password":"s3cr3t"}}""")!!
		assertEquals("s3cr3t", config.rtsp?.password)
		assertFalse("s3cr3t" in config.toString())
		assertNull(PanelConfig().rtsp)
	}

	@Test
	fun `the password does not appear in logs`() {
		assertFalse("s3cr3t" in intercom.toString())
		assertFalse("s3cr3t" in PanelConfig(listOf(intercom)).toString())
		assertFalse("t0ken" in HaConnection("http://ha", "t0ken").toString())
	}

	@Test
	fun `the photos come with their settings, or the defaults`() {
		assertNull(PanelConfig.fromStored("{}")?.photos)
		assertEquals(PhotosConfig(), PanelConfig.fromStored("""{"photos":{}}""")?.photos)
		val photos =
			PanelConfig
				.fromStored(
					"""{"photos":{"idle_minutes":5,"seconds":10,"order":"name","fit":"fill","match_orientation":false,"clock":false,"keep_screen_on":true}}""",
				)?.photos
		assertEquals(
			PhotosConfig(5, 10, PhotoOrder.Name, PhotoFit.Fill, matchOrientation = false, clock = false, keepScreenOn = true),
			photos,
		)
	}
}
