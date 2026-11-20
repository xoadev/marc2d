package dev.xoa.marc2d

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "marc2d"
private val photoClient by lazy { HttpClient(OkHttp) }

// A phone photo is a few MB: anything far bigger is not one, and would not fit in the panel's memory decoded.
private const val MAX_PHOTO_BYTES = 40L * 1024 * 1024
private const val CROSSFADE_MILLIS = 1_000

// With no photo that loads, it tries again after this instead of asking HA in a tight loop.
private val NOTHING_LOADED_RETRY = 30.seconds
private val CLOCK_SHADOW = Shadow(Color.Black, Offset(0f, 2f), blurRadius = 8f)

/**
 * The order the screensaver shows the photos in. It keeps its place between one screensaver and the next, so the same
 * first photos do not show every time.
 *
 * It follows each photo by its path, without the signature: HA signs them again on each refresh, and a pass goes on
 * with the new addresses. A photo removed meanwhile is skipped, and a new one waits for the next pass.
 */
class PhotoPlaylist(
	private val random: Random = Random.Default,
) {
	private var pass = emptyList<String>()
	private var position = 0

	fun next(
		photos: List<String>,
		order: PhotoOrder,
	): String? {
		val byPath = photos.associateBy { it.substringBefore('?') }
		// The rest of this pass, and if nothing is left of it, a whole new one.
		repeat(2) {
			while (position < pass.size) {
				byPath[pass[position++]]?.let { return it }
			}
			pass =
				when (order) {
					PhotoOrder.Shuffle -> byPath.keys.shuffled(random)
					PhotoOrder.Name -> byPath.keys.toList()
				}
			position = 0
		}
		return null
	}
}

/**
 * The screensaver: the photos from HA, full screen, one every [PhotosConfig.seconds], with the time over them if
 * configured. The next one downloads while the current one shows. It only works while the panel's screen is on: with
 * the screen off, nothing is downloaded.
 */
@Composable
fun PhotoScreen(
	photos: List<String>,
	haUrl: String,
	config: PhotosConfig,
	playlist: PhotoPlaylist,
	modifier: Modifier = Modifier,
) {
	var shown by remember { mutableStateOf<Bitmap?>(null) }
	val currentPhotos by rememberUpdatedState(photos)
	val currentConfig by rememberUpdatedState(config)
	val lifecycle = LocalLifecycleOwner.current.lifecycle
	BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
		val screen = with(LocalDensity.current) { ScreenSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
		LaunchedEffect(haUrl, screen) {
			lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
				showPhotos(
					nextPhoto = { playlist.next(currentPhotos, currentConfig.order) },
					load = { url -> loadPhoto(HaProtocol.mediaUrl(url, haUrl), screen) },
					seconds = { currentConfig.seconds },
					show = { shown = it },
				)
			}
		}
		Crossfade(targetState = shown, animationSpec = tween(CROSSFADE_MILLIS), label = "photo") { bitmap ->
			if (bitmap != null) {
				Image(
					bitmap = bitmap.asImageBitmap(),
					contentDescription = null,
					contentScale = if (config.fit == PhotoFit.Fill) ContentScale.Crop else ContentScale.Fit,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
		if (config.clock) {
			PhotoClock(Modifier.align(Alignment.BottomStart).padding(24.dp))
		}
	}
}

private data class ScreenSize(
	val width: Int,
	val height: Int,
)

/** Shows a photo, waits while the next one downloads, and so on, for as long as it is on screen. */
private suspend fun showPhotos(
	nextPhoto: () -> String?,
	load: suspend (String) -> Bitmap?,
	seconds: () -> Int,
	show: (Bitmap) -> Unit,
) {
	coroutineScope {
		var next = loadNext(nextPhoto, load)
		while (true) {
			val current = next
			if (current == null) {
				delay(NOTHING_LOADED_RETRY)
				next = loadNext(nextPhoto, load)
				continue
			}
			show(current)
			val following = async { loadNext(nextPhoto, load) }
			delay(seconds().seconds)
			next = following.await()
		}
	}
}

/** The next photo that loads, trying each one of a pass at most once: those that fail are skipped. */
private suspend fun loadNext(
	nextPhoto: () -> String?,
	load: suspend (String) -> Bitmap?,
): Bitmap? {
	val tried = mutableSetOf<String>()
	while (true) {
		val url = nextPhoto() ?: return null
		if (!tried.add(url.substringBefore('?'))) return null
		load(url)?.let { return it }
	}
}

/** The photo, decoded no larger than the screen needs; nothing, and why in the log, if it cannot be shown. */
private suspend fun loadPhoto(
	url: String,
	screen: ScreenSize,
): Bitmap? {
	// The signature is in the query: the log only gets the path.
	val path = url.substringBefore('?')
	return try {
		val bytes =
			withContext(Dispatchers.IO) {
				val response = photoClient.get(url)
				val length = response.contentLength()
				when {
					!response.status.isSuccess() -> {
						Log.w(TAG, "Photo $path: HTTP ${response.status.value}. If it is 401 or 403, its signature expired or HA cannot reach the file")
						null
					}

					length != null && length > MAX_PHOTO_BYTES -> {
						Log.w(TAG, "Photo $path: skipped, $length bytes is larger than the $MAX_PHOTO_BYTES the panel accepts")
						null
					}

					else -> {
						response.readRawBytes()
					}
				}
			} ?: return null
		withContext(Dispatchers.Default) { decode(bytes, screen) }
	} catch (e: CancellationException) {
		throw e
	} catch (e: Exception) {
		Log.w(TAG, "Photo $path: could not download or decode it (${e::class.simpleName}: ${e.message})")
		null
	}
}

/** Android turns it upright from its EXIF data. A 12 MP photo is scaled down while decoding, so it never fills the memory. */
private fun decode(
	bytes: ByteArray,
	screen: ScreenSize,
): Bitmap =
	ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
		val size = info.size
		// The largest step that still leaves at least the screen's pixels, whether it is shown whole or cropped.
		val sample = minOf(size.width / screen.width.coerceAtLeast(1), size.height / screen.height.coerceAtLeast(1))
		decoder.setTargetSampleSize(sample.coerceAtLeast(1))
	}

/** The time and date, small and with a shadow, to be read over any photo. */
@Composable
private fun PhotoClock(modifier: Modifier = Modifier) {
	val now = rememberNow()
	val locale = Locale.getDefault()
	val style = TextStyle(color = Color.White, shadow = CLOCK_SHADOW)
	Column(modifier) {
		Text(ClockFormat.time(now), style = style.copy(fontSize = 56.sp, fontWeight = FontWeight.Light))
		Text(ClockFormat.date(now, locale, DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")), style = style.copy(fontSize = 20.sp))
	}
}
