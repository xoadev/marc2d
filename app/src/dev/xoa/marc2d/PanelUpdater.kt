package dev.xoa.marc2d

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Copies everything, and says whether it has that hash: what was copied is only good if it does. */
internal fun copyVerifying(
	input: InputStream,
	output: OutputStream,
	sha256: String,
): Boolean {
	val digest = MessageDigest.getInstance("SHA-256")
	val buffer = ByteArray(COPY_BUFFER)
	while (true) {
		val read = input.read(buffer)
		if (read < 0) break
		digest.update(buffer, 0, read)
		output.write(buffer, 0, read)
	}
	val actual = digest.digest().joinToString("") { "%02x".format(it) }
	return MessageDigest.isEqual(actual.toByteArray(), sha256.lowercase().toByteArray())
}

/** Android checks the signature, but only the hash says it is the file that HA was told about. */
internal fun isUpdateUrl(url: String): Boolean = url.startsWith("https://")

/** Installs without asking anyone, as the panel is the Device Owner. Otherwise Android refuses it, and that is reported. */
class PanelUpdater(
	private val context: Context,
) {
	suspend fun install(
		url: String,
		sha256: String,
	) = withContext(Dispatchers.IO) {
		if (!isUpdateUrl(url)) throw IOException("Not an HTTPS address")
		val installer = context.packageManager.packageInstaller
		val params =
			PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
				setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
			}
		val id = installer.createSession(params)
		try {
			installer.openSession(id).use { session ->
				val connection = URL(url).openConnection() as HttpURLConnection
				connection.connectTimeout = TIMEOUT_MS
				connection.readTimeout = TIMEOUT_MS
				try {
					val verified =
						connection.inputStream.use { input ->
							session.openWrite("marc2d.apk", 0, -1).use { output ->
								val good = copyVerifying(input, output, sha256)
								session.fsync(output)
								good
							}
						}
					if (!verified) throw IOException("The hash does not match")
				} finally {
					connection.disconnect()
				}
				val result =
					PendingIntent.getBroadcast(
						context,
						id,
						Intent(context, UpdateResultReceiver::class.java),
						// Mutable: Android adds the result to it.
						PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
					)
				session.commit(result.intentSender)
			}
		} catch (e: Exception) {
			installer.abandonSession(id)
			throw e
		}
	}

	private companion object {
		const val TIMEOUT_MS = 30_000
	}
}

/** On success this process is gone before it gets here: only what went wrong arrives. */
class UpdateResultReceiver : BroadcastReceiver() {
	override fun onReceive(
		context: Context,
		intent: Intent,
	) {
		val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
		if (status != PackageInstaller.STATUS_SUCCESS) {
			Log.w("marc2d", "Update refused: $status")
			(context.applicationContext as Marc2dApp).runtime.updateFailed()
		}
	}
}

/** Device Owners can open their screen from here, which is what brings the panel back after an update. */
class UpdatedReceiver : BroadcastReceiver() {
	override fun onReceive(
		context: Context,
		intent: Intent,
	) {
		context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
	}
}

private const val COPY_BUFFER = 64 * 1024
