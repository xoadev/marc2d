package dev.xoa.marc2d

import android.telecom.Call
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val GRANDMA = Contact("Grandma", "+34 600 12-34-56")
private val EMERGENCIES = Contact("Emergencies", "112")
private val CONTACTS = listOf(GRANDMA, EMERGENCIES)

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

	/** The network and HA write the same number differently. */
	@Test
	fun `a call takes the name of the contact with its number`() {
		assertEquals(GRANDMA, contactFor(CONTACTS, "600123456"))
		assertEquals(GRANDMA, contactFor(CONTACTS, "0034600123456"))
		assertEquals(EMERGENCIES, contactFor(CONTACTS, "112"))
	}

	/** A short number only matches itself: 112 is not the end of every number that ends in 112. */
	@Test
	fun `other numbers have no name`() {
		assertNull(contactFor(CONTACTS, "600123457"))
		assertNull(contactFor(CONTACTS, "981000112"))
		assertNull(contactFor(CONTACTS, "12"))
		assertNull(contactFor(CONTACTS, null))
	}
}
