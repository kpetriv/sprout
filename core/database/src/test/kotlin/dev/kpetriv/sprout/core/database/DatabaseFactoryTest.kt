package dev.kpetriv.sprout.core.database

import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseFactoryTest {
    @Test
    fun rawKeySpecIsSqlCipherBlobLiteral() {
        val key = ByteArray(32) { it.toByte() } + byteArrayOf()
        key[31] = 0xFF.toByte()
        val expected = "x'" + (0 until 31).joinToString("") { "%02x".format(it) } + "ff'"

        val spec = DatabaseFactory.rawKeySpec(key)

        assertEquals(67, spec.size)
        assertEquals(expected, spec.decodeToString())
    }

    @Test
    fun passphraseSecretNameIsStable() {
        // Renaming the secret would orphan every existing encrypted database.
        assertEquals("db.passphrase", DatabaseFactory.PASSPHRASE_SECRET)
        assertEquals("sprout.db", DatabaseFactory.DATABASE_NAME)
    }
}
