package dev.kpetriv.sprout.core.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dev.kpetriv.sprout.core.crypto.SecretStore
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Builds [SproutDatabase].
 *
 * **Production ([encrypted])** uses SQLCipher for Android 4.x through its `SupportOpenHelperFactory`. The key is a
 * 256-bit secret from [SecretStore] under [PASSPHRASE_SECRET], generated once and wrapped by an Android Keystore
 * AES key (`core/crypto`). It is handed to SQLCipher as a raw key (`x'<64 hex digits>'`), so SQLCipher uses it
 * directly instead of stretching it: the secret is already uniformly random, and skipping the KDF keeps opening
 * fast. Everything else is the SQLCipher 4 default:
 * - AES-256-CBC per page, page size 4096, a random per-database salt in the first 16 bytes of the file;
 * - HMAC-SHA512 over every page (tampering is detected on read);
 * - no plaintext header;
 * - PBKDF2-HMAC-SHA512 with 256,000 iterations would apply to a passphrase, but is bypassed by the raw key.
 *
 * On every connection, after keying, `PRAGMA cipher_memory_security = ON` makes SQLCipher wipe and lock the memory
 * it allocates. It is off by default in current SQLCipher releases for speed; the ledger is small, so the cost is
 * acceptable.
 *
 * The key bytes stay referenced by SQLCipher's connection pool for as long as the database is open, because the
 * pool keys every new connection; they cannot be zeroed earlier.
 *
 * The secret is only created when the database file does not exist yet (`createIfMissing`). If the file exists but
 * its secret is gone, opening fails with `SecretUnavailableException` instead of minting a new key that could never
 * open the existing data.
 *
 * JVM (Robolectric) tests use plain in-memory Room on the platform SQLite (see the test sources), so they never need
 * the SQLCipher native library. Encryption itself is covered by the instrumented `EncryptedDatabaseTest`.
 */
object DatabaseFactory {
    const val DATABASE_NAME: String = "sprout.db"

    /** [SecretStore] name of the database key. */
    const val PASSPHRASE_SECRET: String = "db.passphrase"

    private const val SQLCIPHER_LIBRARY = "sqlcipher"

    /**
     * The production, SQLCipher-encrypted database in the app's database directory. Returns immediately: the key
     * is fetched from [secretStore] and the file opened on first use, which Room always does off the main thread.
     */
    @Suppress("SpreadOperator") // Room takes migrations as varargs; the array is tiny and copied once at startup.
    fun encrypted(context: Context, secretStore: SecretStore): SproutDatabase =
        Room.databaseBuilder(context.applicationContext, SproutDatabase::class.java, DATABASE_NAME)
            .openHelperFactory(KeyedOnOpenFactory(context.applicationContext, secretStore))
            .addMigrations(*Migrations.ALL)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    /** Wraps the raw key bytes in SQLCipher's raw-key syntax `x'<hex>'`. The caller zeroes [key]. */
    internal fun rawKeySpec(key: ByteArray): ByteArray {
        val spec = ByteArray(key.size * 2 + RAW_KEY_OVERHEAD)
        spec[0] = 'x'.code.toByte()
        spec[1] = '\''.code.toByte()
        key.forEachIndexed { index, byte ->
            val value = byte.toInt() and BYTE_MASK
            spec[2 + index * 2] = HEX[value ushr NIBBLE_BITS].code.toByte()
            spec[2 + index * 2 + 1] = HEX[value and NIBBLE_MASK].code.toByte()
        }
        spec[spec.size - 1] = '\''.code.toByte()
        return spec
    }

    private const val RAW_KEY_OVERHEAD = 3
    private const val BYTE_MASK = 0xFF
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F
    private const val HEX = "0123456789abcdef"

    /** Turns on SQLCipher's memory wiping for each connection once it is keyed. */
    private object MemorySecurityHook : SQLiteDatabaseHook {
        override fun preKey(connection: SQLiteConnection) = Unit

        override fun postKey(connection: SQLiteConnection) {
            connection.executeRaw("PRAGMA cipher_memory_security = ON", null, null)
        }
    }

    /**
     * Defers fetching the key (a suspend, Keystore-backed call) until Room first opens the database on one of its
     * background threads, so building the database (for example in a Hilt provider) never blocks on Keystore.
     */
    private class KeyedOnOpenFactory(private val context: Context, private val secretStore: SecretStore) :
        SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
            KeyedOnOpenHelper(configuration) {
                System.loadLibrary(SQLCIPHER_LIBRARY)
                // Checked at first open, before SQLCipher creates the file.
                val createIfMissing = !context.getDatabasePath(DATABASE_NAME).exists()
                val keySpec = runBlocking {
                    secretStore.getOrCreate(PASSPHRASE_SECRET, createIfMissing = createIfMissing)
                }.use { secret ->
                    val raw = secret.copyBytes()
                    try {
                        rawKeySpec(raw)
                    } finally {
                        raw.fill(0)
                    }
                }
                SupportOpenHelperFactory(keySpec, MemorySecurityHook, false)
            }
    }

    private class KeyedOnOpenHelper(
        private val configuration: SupportSQLiteOpenHelper.Configuration,
        private val factory: () -> SupportSQLiteOpenHelper.Factory,
    ) : SupportSQLiteOpenHelper {
        private var delegate: SupportSQLiteOpenHelper? = null
        private var writeAheadLogging = false

        @Synchronized
        private fun delegate(): SupportSQLiteOpenHelper = delegate ?: factory().create(configuration).also {
            it.setWriteAheadLoggingEnabled(writeAheadLogging)
            delegate = it
        }

        override val databaseName: String? get() = configuration.name

        override val writableDatabase: SupportSQLiteDatabase get() = delegate().writableDatabase

        override val readableDatabase: SupportSQLiteDatabase get() = delegate().readableDatabase

        @Synchronized
        override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
            writeAheadLogging = enabled
            delegate?.setWriteAheadLoggingEnabled(enabled)
        }

        @Synchronized
        override fun close() {
            delegate?.close()
        }
    }
}
