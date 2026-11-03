package dev.kpetriv.sprout.core.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/** Exercises the Keystore- and JNI-backed implementations that JVM unit tests cannot load. */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val random = SecureRandom()

    @Test
    fun deviceKeySignsAndJceVerifies() = runTest {
        val store = AndroidKeystoreDeviceKeyStore(context, Dispatchers.IO)
        val handle = store.deviceSigningKey()
        assertEquals(KeyAliases.DEVICE_SIGNING, handle.alias)
        val payload = "event".encodeToByteArray()
        val signature = JceEcdsaSigner(handle).sign(payload)
        assertTrue(JceEcdsaVerifier().verify(store.publicKey(), payload, signature))
        assertFalse(JceEcdsaVerifier().verify(store.publicKey(), "other".encodeToByteArray(), signature))
        // Same key on a second lookup, from a fresh instance.
        assertEquals(store.publicKey(), AndroidKeystoreDeviceKeyStore(context, Dispatchers.IO).publicKey())
        store.keyProperties()
    }

    @Test
    fun wrappedSecretRoundTripsThroughKeystoreAndFiles() = runTest {
        val store = WrappedSecretStore(
            AndroidKeystoreKeyWrapper(context),
            FilesDirBlobStorage(context, Dispatchers.IO),
            random,
            Dispatchers.IO,
        )
        val first = store.getOrCreate("instrumented.test").use { it.copyBytes() }
        val second = store.getOrCreate("instrumented.test").use { it.copyBytes() }
        assertArrayEquals(first, second)
        FilesDirBlobStorage(context, Dispatchers.IO).delete("instrumented.test")
    }

    @Test
    fun argon2idHashesAndVerifies() = runTest {
        val hasher = Argon2idPinHasher(random, Dispatchers.Default)
        val encoded = hasher.hash("4821".toCharArray())
        assertTrue(encoded.startsWith("v1\$argon2id\$m=65536,t=3,p=1\$"))
        assertTrue(hasher.verify("4821".toCharArray(), encoded))
        assertFalse(hasher.verify("4822".toCharArray(), encoded))
        assertFalse(hasher.needsRehash(encoded))

        val legacy = Pbkdf2PinHasher(random, Dispatchers.Default, iterations = 1_000).hash("4821".toCharArray())
        assertTrue(hasher.verify("4821".toCharArray(), legacy))
        assertTrue(hasher.needsRehash(legacy))
    }
}
