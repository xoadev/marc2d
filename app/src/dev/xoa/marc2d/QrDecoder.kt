package dev.xoa.marc2d

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** ZXing, because ML Kit depends on Google services, which the panels do not have. */
object QrDecoder {
	private val HINTS =
		mapOf(
			DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
			DecodeHintType.TRY_HARDER to true,
		)

	/** [luminance] is one byte per pixel, as in the Y plane of a camera frame. */
	fun decode(
		luminance: ByteArray,
		rowStride: Int,
		width: Int,
		height: Int,
	): String? {
		val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
		// The global binarizer sometimes reads the blurred image of a fixed-focus camera that the hybrid one misses.
		return read(BinaryBitmap(HybridBinarizer(source))) ?: read(BinaryBitmap(GlobalHistogramBinarizer(source)))
	}

	private fun read(bitmap: BinaryBitmap): String? =
		try {
			QRCodeReader().decode(bitmap, HINTS).text
		} catch (_: NotFoundException) {
			null
		} catch (_: ChecksumException) {
			null
		} catch (_: FormatException) {
			null
		}
}
