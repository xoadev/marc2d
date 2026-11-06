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
		assertEquals(PanelConfig(sensors = false), PanelConfig.fromStored("""{"sensors":false,"gone":1}"""))
		assertNull(PanelConfig.fromStored("""{"intercoms":"not a list"}"""))
		assertNull(PanelConfig.fromStored("""{"intercoms":[{"name":"missing the rest"}]}"""))
		assertNull(PanelConfig.fromStored("not json"))
	}

	@Test
	fun `an intercom's audio is standard unless it says otherwise`() {
		assertEquals(PcmFormat.STANDARD, intercom.audio)
		val stored = PanelConfig.fromStored("""{"intercoms":[{"name":"P","server":"s","extension":"e","password":"p","audio":"pcm_le_16000"}]}""")
		assertEquals(PcmFormat(16_000, true), PcmFormat.parse(stored!!.intercoms.single().audio))
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
}
