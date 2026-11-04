package dev.xoa.marc2d

import android.content.Context
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
			.map { json -> json?.let { STORED.decodeFromString<PanelConfig>(it) } ?: PanelConfig() }

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
		// A version of the app may have stored fields that this one no longer has.
		val STORED = Json { ignoreUnknownKeys = true }
		val URL = stringPreferencesKey("ha_url")
		val TOKEN = stringPreferencesKey("ha_token")
		val PANEL = stringPreferencesKey("panel")
		val CONFIG = stringPreferencesKey("config")
	}
}
