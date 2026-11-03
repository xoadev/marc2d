package dev.xoa.marc2d

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PanelConfigTest {
	private val intercom = IntercomConfig("Parking", "192.0.2.8", "9901#1", "s3cr3t")

	@Test
	fun `escapes the hash of the extension in the URI`() {
		assertEquals("sip:9901%231@192.0.2.8", intercom.sipIdentity)
	}

	@Test
	fun `the password does not appear in logs`() {
		assertFalse("s3cr3t" in intercom.toString())
		assertFalse("s3cr3t" in PanelConfig(listOf(intercom)).toString())
		assertFalse("t0ken" in HaConnection("http://ha", "t0ken").toString())
	}
}
