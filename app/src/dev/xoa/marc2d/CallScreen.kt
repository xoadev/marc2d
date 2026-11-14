package dev.xoa.marc2d

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/** The camera to show while it rings, when the intercom's own video only starts on answering, in the SIP call. */
fun ringingCamera(
	call: IntercomCall,
	cameras: List<CameraConfig>,
): CameraConfig? = if (call.answered || call.live) null else cameras.firstOrNull { it.intercom == call.name }

@Composable
fun CallScreen(
	call: IntercomCall,
	camera: CameraConfig?,
	connection: HaConnection?,
	onVideoView: (TextureView?) -> Unit,
	onLiveSurface: (Surface?) -> Unit,
	onAnswer: () -> Unit,
	onHangUp: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Box(
		modifier =
			modifier
				.fillMaxSize()
				.background(Color.Black),
	) {
		if (call.live) {
			LiveVideo(onLiveSurface)
		} else {
			AndroidView(
				factory = { context -> TextureView(context).also(onVideoView) },
				onRelease = { onVideoView(null) },
				modifier = Modifier.fillMaxSize(),
			)
		}
		if (camera != null && connection != null) CameraView(camera, connection, active = true)
		Text(
			text = if (call.answered) call.name else "Call from ${call.name}",
			color = Color.White,
			fontSize = 28.sp,
			modifier =
				Modifier
					.align(Alignment.TopCenter)
					.padding(32.dp),
		)
		if (!call.answered) {
			PixelButton(
				icon = PixelIcon.Talk,
				description = "Answer",
				onClick = onAnswer,
				modifier =
					Modifier
						.align(Alignment.BottomStart)
						.padding(32.dp),
			)
		}
		// Hang up never moves and is far from Answer: a double tap does not end the call.
		PixelButton(
			icon = PixelIcon.HangUp,
			description = "Hang up",
			onClick = onHangUp,
			color = PixelRed,
			modifier =
				Modifier
					.align(Alignment.BottomEnd)
					.padding(32.dp),
		)
	}
}

/** The intercom's RTSP video: its surface goes to the decoder while it exists. */
@Composable
private fun LiveVideo(onSurface: (Surface?) -> Unit) {
	AndroidView(
		factory = { context ->
			TextureView(context).apply {
				surfaceTextureListener =
					object : TextureView.SurfaceTextureListener {
						private var surface: Surface? = null

						override fun onSurfaceTextureAvailable(
							texture: SurfaceTexture,
							width: Int,
							height: Int,
						) {
							surface = Surface(texture).also(onSurface)
						}

						override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
							onSurface(null)
							surface?.release()
							surface = null
							return true
						}

						override fun onSurfaceTextureSizeChanged(
							texture: SurfaceTexture,
							width: Int,
							height: Int,
						) = Unit

						override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
					}
			}
		},
		modifier = Modifier.fillMaxSize(),
	)
}
