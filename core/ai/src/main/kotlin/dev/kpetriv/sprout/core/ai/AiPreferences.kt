package dev.kpetriv.sprout.core.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * The parent's AI settings (D-M6-6).
 *
 * @property enabled whether the on-device model may be used; the rules path runs either way. On by default.
 * @property downloadConsent whether the parent agreed to the model download (Settings asks once); until then
 *   [AiGateway.requestDownload] is never sent to AICore. Off by default.
 */
data class AiSettings(val enabled: Boolean = true, val downloadConsent: Boolean = false)

/** Stores [AiSettings] on this device. Not synced: each device has its own model. */
interface AiPreferences {
    val settings: Flow<AiSettings>

    suspend fun setEnabled(enabled: Boolean)

    suspend fun setDownloadConsent(consent: Boolean)

    /** The settings now. */
    suspend fun current(): AiSettings = settings.first()
}

/** [AiPreferences] in a Preferences [DataStore] (file `ai_preferences`, see `AiModule`). */
class DataStoreAiPreferences(private val store: DataStore<Preferences>) : AiPreferences {
    override val settings: Flow<AiSettings> = store.data
        // An unreadable file reads as the defaults, which never download anything.
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            AiSettings(
                enabled = preferences[ENABLED] ?: AiSettings().enabled,
                downloadConsent = preferences[DOWNLOAD_CONSENT] ?: AiSettings().downloadConsent,
            )
        }
        .distinctUntilChanged()

    override suspend fun setEnabled(enabled: Boolean) {
        store.edit { it[ENABLED] = enabled }
    }

    override suspend fun setDownloadConsent(consent: Boolean) {
        store.edit { it[DOWNLOAD_CONSENT] = consent }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("enabled")
        val DOWNLOAD_CONSENT = booleanPreferencesKey("download_consent")
    }
}
