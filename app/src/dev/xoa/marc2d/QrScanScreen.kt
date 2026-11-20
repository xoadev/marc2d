package dev.xoa.marc2d

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Starts with the front camera, because on the wall the rear one faces the wall; off the wall, the rear one focuses. */
@Composable
fun QrScanScreen(
	onInvite: (EnrollmentInvite) -> Unit,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	var granted by remember {
		mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
	}
	var front by rememberSaveable { mutableStateOf(true) }
	val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
	LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

	Box(modifier.fillMaxSize().background(Color.Black)) {
		if (granted) {
			CameraPreview(front, onInvite, Modifier.fillMaxSize())
		} else {
			Text("Camera permission is missing", color = PixelWhite, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
		}
		PixelButton(PixelIcon.Close, "Close", onClose, Modifier.align(Alignment.TopEnd).padding(16.dp), size = CLOSE_BUTTON)
		PixelTextButton("Switch camera", { front = !front }, Modifier.align(Alignment.BottomCenter).padding(16.dp))
	}
}

@Composable
private fun CameraPreview(
	front: Boolean,
	onInvite: (EnrollmentInvite) -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current
	val currentOnInvite by rememberUpdatedState(onInvite)
	val previewView = remember { PreviewView(context) }

	DisposableEffect(lifecycleOwner, front) {
		val analysisThread = Executors.newSingleThreadExecutor()
		val mainThread = ContextCompat.getMainExecutor(context)
		val found = AtomicBoolean(false)
		val analysis =
			ImageAnalysis
				.Builder()
				.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
				.setResolutionSelector(ANALYSIS_RESOLUTION)
				.build()
				.apply {
					setAnalyzer(analysisThread) { frame ->
						val invite = frame.use { it.readQr() }?.let(EnrollmentInvite::parse)
						if (invite != null && found.compareAndSet(false, true)) mainThread.execute { currentOnInvite(invite) }
					}
				}
		val preview = Preview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
		val future = ProcessCameraProvider.getInstance(context)
		var disposed = false
		future.addListener(
			{
				if (!disposed) {
					val provider = future.get()
					val wanted = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
					val other = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
					val camera = if (provider.hasCamera(wanted)) wanted else other
					provider.unbindAll()
					provider.bindToLifecycle(lifecycleOwner, camera, preview, analysis)
				}
			},
			mainThread,
		)
		onDispose {
			disposed = true
			if (future.isDone) future.get().unbindAll()
			analysisThread.shutdown()
		}
	}
	AndroidView(factory = { previewView }, modifier = modifier)
}

// The default, 640x480, leaves too few pixels per module for a QR code seen from afar.
private val ANALYSIS_RESOLUTION =
	ResolutionSelector
		.Builder()
		.setResolutionStrategy(ResolutionStrategy(Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
		.build()

private fun ImageProxy.readQr(): String? {
	val plane = planes[0]
	val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
	return QrDecoder.decode(bytes, plane.rowStride, width, height)
}
