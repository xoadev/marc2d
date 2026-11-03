package dev.xoa.marc2d

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

@Composable
fun CallScreen(
	call: IntercomCall,
	onVideoView: (TextureView?) -> Unit,
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
		AndroidView(
			factory = { context -> TextureView(context).also(onVideoView) },
			onRelease = { onVideoView(null) },
			modifier = Modifier.fillMaxSize(),
		)
		Text(
			text = if (call.answered) call.name else "Llaman desde ${call.name}",
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
				description = "Descolgar",
				onClick = onAnswer,
				modifier =
					Modifier
						.align(Alignment.BottomStart)
						.padding(32.dp),
			)
		}
		// Colgar no cambia de sitio y está lejos de Descolgar: una doble pulsación no corta la llamada.
		PixelButton(
			icon = PixelIcon.HangUp,
			description = "Colgar",
			onClick = onHangUp,
			color = PixelRed,
			modifier =
				Modifier
					.align(Alignment.BottomEnd)
					.padding(32.dp),
		)
	}
}
