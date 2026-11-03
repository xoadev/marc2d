package dev.xoa.marc2d

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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

/** Looks for the QR code with the front camera: on the wall, the rear one faces the wall. */
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
	val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
	LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

	Box(modifier.fillMaxSize().background(Color.Black)) {
		if (granted) {
			CameraPreview(onInvite, Modifier.fillMaxSize())
		} else {
			Text("Camera permission is missing", color = PixelWhite, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
		}
		PixelButton(PixelIcon.Close, "Close", onClose, Modifier.align(Alignment.TopEnd).padding(16.dp), size = CLOSE_BUTTON)
	}
}

@Composable
private fun CameraPreview(
	onInvite: (EnrollmentInvite) -> Unit,
	modifier: Modifier = Modifier,
) {
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current
	val currentOnInvite by rememberUpdatedState(onInvite)
	val previewView = remember { PreviewView(context) }

	DisposableEffect(lifecycleOwner) {
		val analysisThread = Executors.newSingleThreadExecutor()
		val mainThread = ContextCompat.getMainExecutor(context)
		val found = AtomicBoolean(false)
		val analysis =
			ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().apply {
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
					val camera =
						if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
							CameraSelector.DEFAULT_FRONT_CAMERA
						} else {
							CameraSelector.DEFAULT_BACK_CAMERA
						}
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

private fun ImageProxy.readQr(): String? {
	val plane = planes[0]
	val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
	return QrDecoder.decode(bytes, plane.rowStride, width, height)
}
