package dev.xoa.marc2d

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val cameraClient by lazy { HttpClient(OkHttp) }
private val RETRY = 3.seconds
private val AUTO_CLOSE = 2.minutes
private const val BUFFER_BYTES = 16 * 1024

/** Las cámaras de la configuración, a pantalla completa: se pasa de una a otra deslizando. */
@Composable
fun CameraScreen(
	cameras: List<CameraConfig>,
	connection: HaConnection,
	onTalk: (String) -> Unit,
	onClose: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val pager = rememberPagerState { cameras.size }
	val close by rememberUpdatedState(onClose)
	LaunchedEffect(Unit) {
		delay(AUTO_CLOSE)
		close()
	}
	Box(
		modifier =
			modifier
				.fillMaxSize()
				.background(Color.Black)
				.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
	) {
		HorizontalPager(state = pager, beyondViewportPageCount = 0, modifier = Modifier.fillMaxSize()) { page ->
			CameraView(cameras[page], connection, active = pager.currentPage == page)
		}
		val current = cameras.getOrNull(pager.currentPage)
		Text(
			text = current?.name.orEmpty(),
			color = Color.White,
			fontSize = 24.sp,
			modifier =
				Modifier
					.align(Alignment.TopStart)
					.padding(24.dp),
		)
		current?.intercom?.let { intercom ->
			PixelButton(
				icon = PixelIcon.Talk,
				description = "Hablar",
				onClick = { onTalk(intercom) },
				modifier =
					Modifier
						.align(Alignment.BottomEnd)
						.padding(24.dp),
			)
		}
		PixelButton(
			icon = PixelIcon.Close,
			size = CLOSE_BUTTON,
			description = "Salir",
			onClick = onClose,
			modifier =
				Modifier
					.align(Alignment.TopEnd)
					.padding(24.dp),
		)
		if (cameras.size > 1) {
			Row(
				horizontalArrangement = Arrangement.spacedBy(8.dp),
				modifier =
					Modifier
						.align(Alignment.BottomCenter)
						.padding(24.dp),
			) {
				repeat(cameras.size) { index ->
					Box(
						Modifier
							.size(10.dp)
							.background(if (index == pager.currentPage) Color.White else Color.DarkGray, CircleShape),
					)
				}
			}
		}
	}
}

@Composable
private fun CameraView(
	camera: CameraConfig,
	connection: HaConnection,
	active: Boolean,
) {
	var image by remember { mutableStateOf<Bitmap?>(null) }
	// Solo se descarga la que se ve: cada flujo gasta red y a la cámara.
	LaunchedEffect(camera, connection, active) {
		if (active) streamFrames(camera, connection) { image = it }
	}
	Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
		val current = image
		if (current == null) {
			Text("Conectando…", color = Color.Gray, fontSize = 20.sp)
		} else {
			Image(current.asImageBitmap(), camera.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
		}
	}
}

private suspend fun streamFrames(
	camera: CameraConfig,
	connection: HaConnection,
	onImage: (Bitmap) -> Unit,
) {
	val url = connection.url.trim().trimEnd('/') + "/api/camera_proxy_stream/" + camera.entity
	while (true) {
		try {
			withContext(Dispatchers.IO) {
				cameraClient
					.prepareGet(url) { header("Authorization", "Bearer ${connection.token}") }
					.execute { response ->
						val channel = response.bodyAsChannel()
						val parser = MjpegParser()
						val buffer = ByteArray(BUFFER_BYTES)
						while (true) {
							val read = channel.readAvailable(buffer)
							if (read < 0) break
							parser.feed(buffer, read) { jpeg ->
								BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let(onImage)
							}
						}
					}
			}
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			// La cámara o HA pueden no estar: se sigue mostrando la última imagen y se reintenta.
		}
		delay(RETRY)
	}
}
