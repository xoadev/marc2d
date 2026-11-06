package dev.xoa.marc2d

import java.io.File
import java.io.InputStream
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** The wake phrase model is 40 MB and optional: it is downloaded when voice is turned on, not shipped in the APK. */
class VoiceModel(
	filesDir: File,
) {
	private val root = File(filesDir, "voice-model")
	private val done = File(root, ".complete")

	val path: File
		get() = File(root, MODEL_FOLDER)

	/** Blocking. Throws if the download fails or is not the file this version expects. */
	fun ensure() {
		if (done.exists()) return
		root.deleteRecursively()
		root.mkdirs()
		val zip = File(root, "model.zip")
		download(MODEL_URL, zip)
		val hash = zip.inputStream().use(::sha256)
		check(hash == MODEL_SHA256) { "Unexpected voice model" }
		zip.inputStream().use { unzip(it, root) }
		zip.delete()
		done.createNewFile()
	}

	companion object {
		const val MODEL_FOLDER = "vosk-model-small-es-0.42"
		private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_FOLDER.zip"
		private const val MODEL_SHA256 = "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f"
		private const val DOWNLOAD_TIMEOUT_MILLIS = 30_000

		/** With timeouts: without them, a server that stops answering would hold voice commands until the app restarts. */
		fun download(
			url: String,
			target: File,
			timeoutMillis: Int = DOWNLOAD_TIMEOUT_MILLIS,
		) {
			val connection =
				URI(url).toURL().openConnection().apply {
					connectTimeout = timeoutMillis
					readTimeout = timeoutMillis
				}
			connection.getInputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
		}

		fun sha256(input: InputStream): String {
			val digest = MessageDigest.getInstance("SHA-256")
			DigestInputStream(input, digest).use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
			return digest.digest().joinToString("") { "%02x".format(it) }
		}

		/** Refuses entries that would land outside [target]. */
		fun unzip(
			input: InputStream,
			target: File,
		) {
			val base = target.canonicalFile
			ZipInputStream(input).use { zip ->
				generateSequence { zip.nextEntry }.forEach { entry ->
					val file = File(base, entry.name).canonicalFile
					check(file.path.startsWith(base.path + File.separator)) { "Invalid entry in the voice model" }
					if (entry.isDirectory) {
						file.mkdirs()
					} else {
						file.parentFile?.mkdirs()
						file.outputStream().use { zip.copyTo(it) }
					}
				}
			}
		}
	}
}
