package dev.xoa.marc2d

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EnrollmentTest {
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
