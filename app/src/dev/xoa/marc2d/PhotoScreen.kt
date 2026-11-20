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
import androidx.compose.foundation.layout.offset
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
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
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
private const val READ_BUFFER_BYTES = 64 * 1024

// With no photo that loads, it tries again after this instead of asking HA in a tight loop.
private val NOTHING_LOADED_RETRY = 30.seconds

// Photos this close to square suit either orientation.
private const val SQUARE_TOLERANCE = 0.05f
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

enum class PhotoShape {
	Landscape,
	Portrait,
	Square,
	;

	fun fits(landscapeScreen: Boolean): Boolean = this == Square || (this == Landscape) == landscapeScreen

	companion object {
		/** From the decoded photo, already turned upright: the file's own size may be sideways. */
		fun of(
			width: Int,
			height: Int,
		): PhotoShape =
			when {
				abs(width - height) <= maxOf(width, height) * SQUARE_TOLERANCE -> Square
				width > height -> Landscape
				else -> Portrait
			}
	}
}

/**
 * The shape of each photo, learnt the first time it downloads, to show only those shaped like the screen. HA cannot say
 * it without opening every file, and some sources, such as Immich, only through their own servers.
 *
 * A photo of unknown shape may still fit, so it is downloaded. When no photo can fit, they all show: a black screen
 * would be worse than a photo with bars.
 */
class PhotoShapes {
	private val shapes = mutableMapOf<String, PhotoShape>()

	/** Whether to skip this photo without downloading it. [landscape] is null when the orientation does not matter. */
	fun skip(
		url: String,
		photos: List<String>,
		landscape: Boolean?,
	): Boolean = landscape != null && shapes[path(url)]?.fits(landscape) == false && anyMayFit(photos, landscape)

	/** Learns the shape of a downloaded photo, and says whether to show it. */
	fun accept(
		url: String,
		width: Int,
		height: Int,
		photos: List<String>,
		landscape: Boolean?,
	): Boolean {
		val shape = PhotoShape.of(width, height)
		shapes[path(url)] = shape
		return landscape == null || shape.fits(landscape) || !anyMayFit(photos, landscape)
	}

	private fun anyMayFit(
		photos: List<String>,
		landscape: Boolean,
	): Boolean = photos.any { shapes[path(it)]?.fits(landscape) ?: true }

	private fun path(url: String) = url.substringBefore('?')
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
	shapes: PhotoShapes,
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
				val pick =
					PhotoPick(
						nextPhoto = { playlist.next(currentPhotos, currentConfig.order) },
						load = { url -> loadPhoto(HaProtocol.mediaUrl(url, haUrl), screen) },
						skip = { url -> shapes.skip(url, currentPhotos, screen.wantedLandscape(currentConfig)) },
						accept = { url, bitmap ->
							shapes.accept(url, bitmap.width, bitmap.height, currentPhotos, screen.wantedLandscape(currentConfig))
						},
					)
				showPhotos(pick, seconds = { currentConfig.seconds }, show = { shown = it })
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
) {
	/** The orientation the photos must have, or nothing if any will do. */
	fun wantedLandscape(config: PhotosConfig): Boolean? = if (config.matchOrientation) width > height else null
}

/** How the screensaver chooses its next photo: the order, the download, and which to leave out by their shape. */
private class PhotoPick(
	val nextPhoto: () -> String?,
	val load: suspend (String) -> Bitmap?,
	val skip: (String) -> Boolean,
	val accept: (String, Bitmap) -> Boolean,
)

/** Shows a photo, waits while the next one downloads, and so on, for as long as it is on screen. */
private suspend fun showPhotos(
	pick: PhotoPick,
	seconds: () -> Int,
	show: (Bitmap) -> Unit,
) {
	coroutineScope {
		var next = loadNext(pick)
		while (true) {
			val current = next
			if (current == null) {
				delay(NOTHING_LOADED_RETRY)
				next = loadNext(pick)
				continue
			}
			show(current)
			val following = async { loadNext(pick) }
			delay(seconds().seconds)
			next = following.await()
		}
	}
}

/** The next photo that loads and is wanted, trying each one of a pass at most once: those that fail are skipped. */
private suspend fun loadNext(pick: PhotoPick): Bitmap? {
	val tried = mutableSetOf<String>()
	while (true) {
		val url = pick.nextPhoto() ?: return null
		if (!tried.add(url.substringBefore('?'))) return null
		if (pick.skip(url)) continue
		val bitmap = pick.load(url) ?: continue
		if (pick.accept(url, bitmap)) return bitmap
		bitmap.recycle()
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
		// Streamed: a plain get would hold the whole body in memory before its size could be checked.
		val bytes =
			withContext(Dispatchers.IO) {
				photoClient.prepareGet(url).execute { response ->
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
							readAtMost(response.bodyAsChannel(), MAX_PHOTO_BYTES).also { photo ->
								if (photo == null) Log.w(TAG, "Photo $path: skipped, it is larger than the $MAX_PHOTO_BYTES bytes the panel accepts")
							}
						}
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

/** Everything [channel] has, or nothing once it passes [limit]: a server need not say the size before sending it. */
internal suspend fun readAtMost(
	channel: ByteReadChannel,
	limit: Long,
): ByteArray? {
	val bytes = ByteArrayOutputStream()
	val buffer = ByteArray(READ_BUFFER_BYTES)
	while (true) {
		val read = channel.readAvailable(buffer)
		if (read < 0) return bytes.toByteArray()
		bytes.write(buffer, 0, read)
		if (bytes.size() > limit) return null
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
	// It moves as the main clock does: the photos change behind it, but it would stay on the same pixels for hours.
	val (offsetX, offsetY) = ClockFormat.burnInOffset(now)
	Column(modifier.offset(offsetX.dp, offsetY.dp)) {
		Text(ClockFormat.time(now), style = style.copy(fontSize = 56.sp, fontWeight = FontWeight.Light))
		Text(ClockFormat.date(now, locale, DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")), style = style.copy(fontSize = 20.sp))
	}
}
