package dev.xoa.marc2d

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Display
import android.view.Surface
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** The front camera encoded in H.264, only while [start] and [stop] say so. */
class PanelCamera(
	private val context: Context,
) : H264Source {
	@Volatile private var session: Session? = null

	override suspend fun start(
		onFrame: (H264Frame) -> Unit,
		onFailure: () -> Unit,
	): H264Parameters {
		if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
			throw IOException("No camera permission")
		}
		val started = Session(context, onFrame, onFailure)
		session = started
		try {
			return withTimeout(START_TIMEOUT) { started.open() }
		} catch (e: TimeoutCancellationException) {
			stop()
			// Not a cancellation of whoever called: the camera just did not answer.
			throw IOException("The camera did not start", e)
		} catch (e: CancellationException) {
			stop()
			throw e
		} catch (e: Exception) {
			stop()
			throw e
		}
	}

	override fun requestKeyFrame() {
		session?.requestKeyFrame()
	}

	override fun stop() {
		session?.close()
		session = null
	}

	private class Session(
		private val context: Context,
		private val onFrame: (H264Frame) -> Unit,
		private val onFailure: () -> Unit,
	) {
		private val thread = HandlerThread("marc2d-camera").apply { start() }
		private val handler = Handler(thread.looper)
		private val executor = Executor { handler.post(it) }
		private val parameters = CompletableDeferred<H264Parameters>()
		private var encoder: MediaCodec? = null
		private var inputSurface: Surface? = null
		private var renderer: RotatingRenderer? = null
		private var camera: CameraDevice? = null
		private var capture: CameraCaptureSession? = null
		private var closed = false

		suspend fun open(): H264Parameters {
			val manager = context.getSystemService(CameraManager::class.java)
			val id =
				manager.cameraIdList.firstOrNull {
					manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
				} ?: manager.cameraIdList.firstOrNull() ?: throw IOException("No camera")
			val fpsRange = fpsRange(manager.getCameraCharacteristics(id))
			val rotation = displayRotation()
			// The camera's frames arrive upright for the phone's natural orientation, which on a Pixel is portrait.
			val sideways = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
			val width = if (sideways) LONG_SIDE else SHORT_SIDE
			val height = if (sideways) SHORT_SIDE else LONG_SIDE

			val codec = createEncoder(width, height)
			encoder = codec
			val surface = codec.createInputSurface()
			inputSurface = surface
			codec.start()

			val cameraSurface = onHandler { RotatingRenderer(surface, width, height, rotation, handler).also { renderer = it }.cameraSurface }
			val device = openCamera(manager, id)
			camera = device
			capture = createSession(device, cameraSurface)
			capture?.setRepeatingRequest(
				device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).run {
					addTarget(cameraSurface)
					set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
					build()
				},
				null,
				handler,
			)
			return parameters.await()
		}

		private fun createEncoder(
			width: Int,
			height: Int,
		): MediaCodec {
			val format =
				MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
					setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
					setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
					setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
					setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_SECONDS)
					// A client that joins late needs them in the stream, and an SDP may go stale.
					setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
				}
			return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
				setCallback(EncoderOutput(), handler)
				configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
			}
		}

		private inner class EncoderOutput : MediaCodec.Callback() {
			override fun onInputBufferAvailable(
				codec: MediaCodec,
				index: Int,
			) = Unit

			override fun onOutputBufferAvailable(
				codec: MediaCodec,
				index: Int,
				info: MediaCodec.BufferInfo,
			) {
				if (closed) return
				val buffer = codec.getOutputBuffer(index)
				if (buffer != null && info.size > 0) {
					val data = ByteArray(info.size)
					buffer.position(info.offset)
					buffer.get(data)
					if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
						h264Parameters(data)?.let(parameters::complete)
					} else {
						onFrame(H264Frame(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0))
					}
				}
				codec.releaseOutputBuffer(index, false)
			}

			override fun onError(
				codec: MediaCodec,
				e: MediaCodec.CodecException,
			) = fail(e)

			override fun onOutputFormatChanged(
				codec: MediaCodec,
				format: MediaFormat,
			) = Unit
		}

		private fun fail(cause: Exception) {
			if (closed) return
			if (!parameters.completeExceptionally(cause)) onFailure()
		}

		private fun displayRotation(): Int =
			context
				.getSystemService(DisplayManager::class.java)
				.getDisplay(Display.DEFAULT_DISPLAY)
				?.rotation ?: Surface.ROTATION_0

		/** The lowest rate that reaches ours: more than that would only be thrown away, and it gives the sensor more light. */
		private fun fpsRange(characteristics: CameraCharacteristics): Range<Int> =
			characteristics
				.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
				.orEmpty()
				.filter { it.upper >= FRAME_RATE }
				.minByOrNull { it.upper }
				?: Range(FRAME_RATE, FRAME_RATE)

		private suspend fun <T> onHandler(block: () -> T): T =
			suspendCancellableCoroutine { continuation ->
				handler.post {
					try {
						continuation.resume(block())
					} catch (e: RuntimeException) {
						continuation.resumeWithException(e)
					}
				}
			}

		@SuppressLint("MissingPermission") // Checked in start.
		private suspend fun openCamera(
			manager: CameraManager,
			id: String,
		): CameraDevice =
			suspendCancellableCoroutine { continuation ->
				manager.openCamera(
					id,
					executor,
					object : CameraDevice.StateCallback() {
						override fun onOpened(device: CameraDevice) {
							if (continuation.isActive) continuation.resume(device) else device.close()
						}

						override fun onDisconnected(device: CameraDevice) {
							// Another use with more priority took it, such as scanning a QR code.
							device.close()
							lost(continuation)
						}

						override fun onError(
							device: CameraDevice,
							error: Int,
						) {
							device.close()
							lost(continuation)
						}

						private fun lost(continuation: kotlinx.coroutines.CancellableContinuation<CameraDevice>) {
							val problem = IOException("Camera lost")
							if (continuation.isActive) continuation.resumeWithException(problem) else fail(problem)
						}
					},
				)
			}

		private suspend fun createSession(
			device: CameraDevice,
			surface: Surface,
		): CameraCaptureSession =
			suspendCancellableCoroutine { continuation ->
				val output =
					OutputConfiguration(surface).apply {
						// A person on the other side should be seen as they are: "auto" would mirror the front camera.
						mirrorMode = OutputConfiguration.MIRROR_MODE_NONE
					}
				device.createCaptureSession(
					SessionConfiguration(
						SessionConfiguration.SESSION_REGULAR,
						listOf(output),
						executor,
						object : CameraCaptureSession.StateCallback() {
							override fun onConfigured(session: CameraCaptureSession) {
								continuation.resume(session)
							}

							override fun onConfigureFailed(session: CameraCaptureSession) {
								continuation.resumeWithException(IOException("Camera session failed"))
							}
						},
					),
				)
			}

		fun requestKeyFrame() {
			handler.post {
				if (!closed) {
					encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
				}
			}
		}

		fun close() {
			handler.post {
				closed = true
				// The errors from here on only say that something was already closing.
				runCatching { capture?.close() }
				runCatching { camera?.close() }
				runCatching { renderer?.close() }
				runCatching { encoder?.stop() }
				runCatching { encoder?.release() }
				runCatching { inputSurface?.release() }
				thread.quitSafely()
			}
		}
	}

	private companion object {
		val START_TIMEOUT = 8.seconds
		const val LONG_SIDE = 1280
		const val SHORT_SIDE = 720
		const val BITRATE = 2_000_000
		const val KEY_FRAME_SECONDS = 2
		const val FRAME_RATE = 15
	}
}

/** RTSP has no rotation flag, as a video file does: frames have to be drawn already turned to match the panel. */
private class RotatingRenderer(
	encoderSurface: Surface,
	private val width: Int,
	private val height: Int,
	rotation: Int,
	handler: Handler,
) {
	private val gl = EglEncoderSurface(encoderSurface)
	private val texture = gl.createExternalTexture()
	private val surfaceTexture = SurfaceTexture(texture)
	private val program = gl.createProgram()
	private val transform = FloatArray(16)
	private val degrees =
		when (rotation) {
			Surface.ROTATION_90 -> 90f
			Surface.ROTATION_180 -> 180f
			Surface.ROTATION_270 -> 270f
			else -> 0f
		}
	private var lastFrameNs = 0L

	val cameraSurface = Surface(surfaceTexture)

	init {
		// The camera's own aspect, whatever the panel's orientation: the transform turns it upright.
		surfaceTexture.setDefaultBufferSize(maxOf(width, height), minOf(width, height))
		surfaceTexture.setOnFrameAvailableListener({ draw() }, handler)
	}

	private fun draw() {
		surfaceTexture.updateTexImage()
		val time = surfaceTexture.timestamp
		// Sensors may only offer more than the frames we want, and the encoder would spend on them.
		if (time - lastFrameNs < MIN_FRAME_NS) return
		lastFrameNs = time
		surfaceTexture.getTransformMatrix(transform)
		gl.draw(program, texture, transform, degrees, width, height, time)
	}

	fun close() {
		surfaceTexture.setOnFrameAvailableListener(null)
		cameraSurface.release()
		surfaceTexture.release()
		gl.release()
	}

	private companion object {
		const val MIN_FRAME_NS = 60_000_000L
	}
}
