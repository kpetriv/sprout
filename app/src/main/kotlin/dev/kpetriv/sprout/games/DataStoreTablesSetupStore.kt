package dev.kpetriv.sprout.games

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.kpetriv.sprout.core.database.LocalPreferenceStore
import dev.kpetriv.sprout.core.games.TablesLevel
import dev.kpetriv.sprout.core.games.TablesSetup
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.feature.games.TablesSetupStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Each kid's last Times tables setup on this device (M9-games §6.3, D-M9-G7), in a Preferences [DataStore] whose file
 * is `LocalDataFiles.tablesSetupFile` (`noBackupFilesDir/datastore/games_tables_setup.preferences_pb`), so the wipe
 * deletes it with the household's data. Never synced, never on the wire.
 *
 * Two keys per kid id: `tables.<kid>`, the tables comma-joined in order ("3,4,6"), and `level.<kid>`, the
 * [TablesLevel] name. A missing, unreadable or invalid value (an unknown level, a table outside 2 to 12, no table)
 * reads as null, and the screens fall back to the difficulty's default.
 *
 * It is a [LocalPreferenceStore]: the wipe, the trial purge and an account switch [clear] it through DataStore's own
 * writer before deleting the file, so a save already queued cannot write the setups back afterwards.
 */
class DataStoreTablesSetupStore(private val store: DataStore<Preferences>) :
    TablesSetupStore,
    LocalPreferenceStore {
    override fun observe(kidId: KidId): Flow<TablesSetup?> = store.data
        // An unreadable file reads as nothing stored: the default setup, never a crash.
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences -> decode(preferences[tablesKey(kidId)], preferences[levelKey(kidId)]) }
        .distinctUntilChanged()

    override suspend fun save(kidId: KidId, setup: TablesSetup) {
        store.edit { preferences ->
            preferences[tablesKey(kidId)] = setup.tables.sorted().joinToString(SEPARATOR)
            preferences[levelKey(kidId)] = setup.level.name
        }
    }

    override suspend fun clear() {
        store.edit { it.clear() }
    }

    internal companion object {
        private const val SEPARATOR = ","

        private fun tablesKey(kidId: KidId) = stringPreferencesKey("tables.${kidId.value}")

        private fun levelKey(kidId: KidId) = stringPreferencesKey("level.${kidId.value}")

        /** The stored setup, or null when either half is missing or not a valid setup. */
        @Suppress("ReturnCount") // Guard clauses: each early return is a value that does not parse.
        fun decode(tables: String?, level: String?): TablesSetup? {
            val parsedLevel = TablesLevel.entries.firstOrNull { it.name == level } ?: return null
            val parsedTables = tables?.split(SEPARATOR)?.map { it.trim().toIntOrNull() ?: return null }?.toSet()
                ?: return null
            if (parsedTables.isEmpty() || parsedTables.any { it !in TablesSetup.TABLES }) return null
            return TablesSetup(parsedTables, parsedLevel)
        }
    }
}
