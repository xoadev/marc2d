package dev.xoa.marc2d

import android.telecom.Call
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneLineTest {
	@Test
	fun `a call rings until Telecom says it is under way`() {
		assertEquals(PhoneCall("600", answered = false), phoneCall(Call.STATE_NEW, "600"))
		assertEquals(PhoneCall("600", answered = false), phoneCall(Call.STATE_RINGING, "600"))
		assertEquals(PhoneCall("600", answered = true), phoneCall(Call.STATE_ACTIVE, "600"))
		assertEquals(PhoneCall("600", answered = true), phoneCall(Call.STATE_HOLDING, "600"))
	}

	/** The screen goes as soon as the call starts going away, not when Telecom removes it. */
	@Test
	fun `a call that is ending leaves the screen`() {
		assertNull(phoneCall(Call.STATE_DISCONNECTING, "600"))
		assertNull(phoneCall(Call.STATE_DISCONNECTED, "600"))
	}
}
