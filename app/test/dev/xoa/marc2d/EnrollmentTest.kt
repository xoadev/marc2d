package dev.xoa.marc2d

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class EnrollmentTest {
	private val invite = EnrollmentInvite("http://192.0.2.20:8123", "ABCD2345")

	@Test
	fun `reads the invite that the integration generates`() {
		val invite = EnrollmentInvite.parse("marc2d://enroll?url=http%3A%2F%2F192.0.2.20%3A8123&code=ABCD2345")
		assertEquals(EnrollmentInvite("http://192.0.2.20:8123", "ABCD2345"), invite)
	}

	@Test
	fun `ignores any other QR code`() {
		listOf(
			"https://example.com",
			"not a uri at all",
			"marc2d://other?url=http%3A%2F%2F192.0.2.20&code=A",
			"marc2d://enroll?code=A",
			"marc2d://enroll?url=http%3A%2F%2F192.0.2.20",
			"marc2d://enroll?url=file%3A%2F%2F%2Fetc&code=A",
		).forEach { assertNull(EnrollmentInvite.parse(it), it) }
	}

	@Test
	fun `does not print the code`() {
		assertFalse("ABCD2345" in EnrollmentInvite("http://192.0.2.20", "ABCD2345").toString())
	}

	@Test
	fun `cancelling is not reported as a wrong address`() {
		runBlocking {
			assertFailsWith<CancellationException> { enrollmentProblem(invite) { throw CancellationException("rotated") } }
		}
	}

	@Test
	fun `explains why it could not enroll`() {
		runBlocking {
			assertNull(enrollmentProblem(invite) {})
			assertEquals("The code is not valid or has expired", enrollmentProblem(invite) { throw EnrollmentRejectedException() })
			assertEquals("Cannot reach Home Assistant at ${invite.url} (IOException)", enrollmentProblem(invite) { throw IOException() })
			assertEquals("That address is not a Home Assistant with marc2d", enrollmentProblem(invite) { error("not JSON") })
		}
	}

	@Test
	fun `decodes a QR code from a frame with row padding`() {
		val text = "marc2d://enroll?url=http%3A%2F%2F192.0.2.20%3A8123&code=ABCD2345"
		val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400)
		val stride = matrix.width + 16
		val frame = ByteArray(stride * matrix.height) { -1 }
		for (y in 0 until matrix.height) {
			for (x in 0 until matrix.width) if (matrix[x, y]) frame[y * stride + x] = 0
		}
		assertEquals(text, QrDecoder.decode(frame, stride, matrix.width, matrix.height))
	}

	@Test
	fun `finds nothing in an empty frame`() {
		assertNull(QrDecoder.decode(ByteArray(100 * 100), 100, 100, 100))
	}
}
