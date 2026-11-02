package dev.kpetriv.sprout.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kpetriv.sprout.core.crypto.InMemoryBlobStorage
import dev.kpetriv.sprout.core.crypto.InMemoryKeyWrapper
import dev.kpetriv.sprout.core.crypto.SecretUnavailableException
import dev.kpetriv.sprout.core.crypto.WrappedSecretStore
import dev.kpetriv.sprout.core.database.entity.KidEntity
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/** Device-only: SQLCipher's native library does not run on the JVM. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val secrets = WrappedSecretStore(
        wrapper = InMemoryKeyWrapper(),
        storage = InMemoryBlobStorage(),
        random = SecureRandom(),
        dispatcher = Dispatchers.IO,
    )

    @Before
    @After
    fun deleteDatabase() {
        context.deleteDatabase(DatabaseFactory.DATABASE_NAME)
    }

    @Test
    fun fileIsEncryptedAndReopensWithTheStoredKey() = runTest {
        val kid = KidEntity(
            kidId = KidId("k1"),
            householdId = HouseholdId("h1"),
            firstName = "Mia",
            avatarId = "avatar-1",
            birthYearMonth = YearMonth(2020, 1),
            uiTier = KidUiTier.SEEDLING,
            archived = false,
        )
        DatabaseFactory.encrypted(context, secrets).apply {
            kidDao().upsert(kid)
            val memorySecurity = openHelper.writableDatabase.query("PRAGMA cipher_memory_security").use {
                it.moveToFirst()
                it.getString(0)
            }
            assertEquals("1", memorySecurity)
            close()
        }

        val file = context.getDatabasePath(DatabaseFactory.DATABASE_NAME).readBytes()
        assertFalse("database file must not be plaintext", file.containsSlice("SQLite format 3".encodeToByteArray()))
        assertFalse("kid name must not be readable on disk", file.containsSlice("Mia".encodeToByteArray()))

        DatabaseFactory.encrypted(context, secrets).apply {
            assertEquals(kid, kidDao().get(KidId("k1")))
            close()
        }
    }

    @Test
    fun anExistingDatabaseWhoseSecretIsGoneFailsInsteadOfMintingANewKey() = runTest {
        DatabaseFactory.encrypted(context, secrets).apply {
            kidDao().count()
            close()
        }
        val emptyStore = WrappedSecretStore(
            wrapper = InMemoryKeyWrapper(),
            storage = InMemoryBlobStorage(),
            random = SecureRandom(),
            dispatcher = Dispatchers.IO,
        )

        val failure = runCatching {
            DatabaseFactory.encrypted(context, emptyStore).apply {
                try {
                    kidDao().count()
                } finally {
                    close()
                }
            }
        }.exceptionOrNull()

        assertTrue(
            "expected SecretUnavailableException, got $failure",
            generateSequence(failure) { it.cause }.any { it is SecretUnavailableException },
        )
        // The store was not allowed to create a replacement secret.
        assertEquals(null, emptyStore.runCatching { getOrCreate(DatabaseFactory.PASSPHRASE_SECRET, false) }.getOrNull())
    }

    private fun ByteArray.containsSlice(slice: ByteArray): Boolean =
        (0..size - slice.size).any { start -> slice.indices.all { this[start + it] == slice[it] } }
}
