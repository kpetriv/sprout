package dev.kpetriv.sprout.core.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals

/**
 * Migration scaffold. Version 1 has no migrations yet; each schema bump adds a `Migration` to [ALL_MIGRATIONS] and a
 * test here that creates the old version, migrates and validates against the exported schema.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SproutDatabase::class.java)

    @Test
    fun version1CreatesAndValidatesAgainstTheExportedSchema() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, 1, true, *ALL_MIGRATIONS).close()
    }

    @Test
    fun roomOpensTheHelperCreatedDatabase() {
        helper.createDatabase(TEST_DB, SproutDatabase.VERSION).close()
        val database = Room.databaseBuilder(RuntimeEnvironment.getApplication(), SproutDatabase::class.java, TEST_DB)
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        try {
            assertEquals(SproutDatabase.VERSION, database.openHelper.writableDatabase.version)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        val ALL_MIGRATIONS = emptyArray<androidx.room.migration.Migration>()
    }
}
