package dev.xoa.marc2d

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "panel")

data class HaConnection(
	val url: String,
	val token: String,
	val panel: String = DEFAULT_PANEL,
) {
	override fun toString(): String = "HaConnection(url=$url, panel=$panel)"

	companion object {
		const val DEFAULT_PANEL = "Panel"
	}
}

/**
 * Keeps in the app's private storage the connection to Home Assistant and the last
 * configuration received, so that the panel handles calls even if Home Assistant is down.
 */
class PanelStore(
	private val context: Context,
) {
	val connection: Flow<HaConnection?> =
		context.dataStore.data
			.map { prefs ->
				val url = prefs[URL]
				val token = prefs[TOKEN]
				if (url.isNullOrBlank() || token.isNullOrBlank()) null else HaConnection(url, token, prefs[PANEL] ?: HaConnection.DEFAULT_PANEL)
			}.distinctUntilChanged()

	val config: Flow<PanelConfig> =
		context.dataStore.data
			.map { prefs -> prefs[CONFIG] }
			.distinctUntilChanged()
			.map { json ->
				json?.let { stored ->
					// Not the exception: its message could include passwords.
					PanelConfig.fromStored(stored) ?: PanelConfig().also { Log.w("marc2d", "Ignoring a stored configuration that cannot be read") }
				} ?: PanelConfig()
			}

	suspend fun saveConnection(connection: HaConnection) {
		context.dataStore.edit { prefs ->
			prefs[URL] = connection.url.trim()
			prefs[TOKEN] = connection.token.trim()
			prefs[PANEL] = connection.panel.trim()
		}
	}

	suspend fun saveConfig(config: PanelConfig) {
		context.dataStore.edit { prefs -> prefs[CONFIG] = Json.encodeToString(config) }
	}

	private companion object {
		val URL = stringPreferencesKey("ha_url")
		val TOKEN = stringPreferencesKey("ha_token")
		val PANEL = stringPreferencesKey("panel")
		val CONFIG = stringPreferencesKey("config")
	}
}
