package dev.kpetriv.sprout.core.crypto

import android.os.Build
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Which implementation each API level gets (D-M4-1), without Robolectric. */
class X25519SelectionTest {
    private val random = SecureRandom()

    @Test
    fun `JCE is chosen only from API 33 and only when it passes its self-test`() {
        assertIs<JceX25519KeyAgreement>(X25519KeyAgreements.select(random, 33) { true })
        assertIs<JceX25519KeyAgreement>(X25519KeyAgreements.select(random, 36) { true })
        assertIs<TinkX25519KeyAgreement>(X25519KeyAgreements.select(random, 33) { false })
        assertIs<TinkX25519KeyAgreement>(X25519KeyAgreements.select(random, 32) { true })
        assertIs<TinkX25519KeyAgreement>(X25519KeyAgreements.select(random, 28) { error("not consulted below 33") })
    }

    @Test
    fun `the JCE self-test passes on this JDK`() {
        assertTrue(X25519KeyAgreements.jceSelfTest())
    }
}

/**
 * The production selection ([X25519KeyAgreements.select] with `Build.VERSION.SDK_INT`, as `CryptoModule` binds it) on
 * the oldest supported API level and the current one, each running the RFC 7748 section 6.1 exchange.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class X25519SelectionRobolectricTest {
    @Test
    fun `the selected implementation computes the RFC 7748 exchange`() {
        val agreement = X25519KeyAgreements.select(SecureRandom(), Build.VERSION.SDK_INT)
        if (Build.VERSION.SDK_INT < X25519KeyAgreements.JCE_XDH_MIN_SDK) {
            assertIs<TinkX25519KeyAgreement>(agreement)
        } else {
            assertIs<JceX25519KeyAgreement>(agreement)
        }
        val alice = X25519PrivateKey.copyOf(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        val bob = X25519PrivateKey.copyOf(hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))
        val expected = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        agreement.sharedSecret(alice, agreement.publicKeyOf(bob)).use { assertContentEquals(expected, it.copyBytes()) }
        agreement.sharedSecret(bob, agreement.publicKeyOf(alice)).use { assertContentEquals(expected, it.copyBytes()) }
    }
}
