package dev.kpetriv.sprout.games

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.kpetriv.sprout.core.games.TablesLevel
import dev.kpetriv.sprout.core.games.TablesSetup
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataStoreTablesSetupStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val leo = KidId("kid-leo")
    private val mia = KidId("kid-mia")

    private fun file() = File(folder.root, "games_tables_setup.preferences_pb")

    private fun TestScope.dataStore(file: File = file()) =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { file }

    @Test
    fun aSavedSetupRoundTripsPerKid() = runTest {
        val store = DataStoreTablesSetupStore(dataStore())
        assertNull(store.observe(leo).first(), "nothing stored yet")

        store.save(leo, TablesSetup(setOf(6, 3, 4), TablesLevel.ROCK_STAR))
        store.save(mia, TablesSetup.MIX)

        assertEquals(TablesSetup(setOf(3, 4, 6), TablesLevel.ROCK_STAR), store.observe(leo).first())
        assertEquals(TablesSetup.MIX, store.observe(mia).first())
    }

    @Test
    fun theSetupIsStoredAsCommaJoinedTablesAndALevelName() = runTest {
        val data = dataStore()
        DataStoreTablesSetupStore(data).save(leo, TablesSetup(setOf(7, 2), TablesLevel.STEADY))

        val preferences = data.data.first()
        assertEquals("2,7", preferences[stringPreferencesKey("tables.kid-leo")])
        assertEquals("STEADY", preferences[stringPreferencesKey("level.kid-leo")])
    }

    @Test
    fun aCorruptOrPartialValueReadsAsNull() = runTest {
        val data = dataStore()
        val store = DataStoreTablesSetupStore(data)
        val cases = listOf(
            "3,x" to "GENTLE",
            "1,3" to "GENTLE",
            "" to "GENTLE",
            "3,4" to "TURBO",
            "3,4" to null,
            null to "GENTLE",
        )
        cases.forEach { (tables, level) ->
            data.edit { preferences ->
                preferences.clear()
                if (tables != null) preferences[stringPreferencesKey("tables.kid-leo")] = tables
                if (level != null) preferences[stringPreferencesKey("level.kid-leo")] = level
            }
            assertNull(store.observe(leo).first(), "$tables / $level")
        }
    }

    @Test
    fun clearRemovesEveryKidsSetup() = runTest {
        val store = DataStoreTablesSetupStore(dataStore())
        store.save(leo, TablesSetup.MIX)
        store.save(mia, TablesSetup(setOf(9), TablesLevel.GENTLE))

        store.clear()

        assertNull(store.observe(leo).first())
        assertNull(store.observe(mia).first())
    }

    @Test
    fun anUnreadableFileReadsAsNothingStored() = runTest {
        val file = file().apply { writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03)) }

        assertNull(DataStoreTablesSetupStore(dataStore(file)).observe(leo).first())
    }
}
