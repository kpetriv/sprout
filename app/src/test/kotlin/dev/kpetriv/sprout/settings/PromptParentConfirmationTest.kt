package dev.kpetriv.sprout.settings

import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.ParentKeyInvalidatedException
import dev.kpetriv.sprout.core.crypto.ParentKeyMismatchException
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.feature.lock.BiometricCancelledException
import dev.kpetriv.sprout.feature.lock.UnavailableParentAuthorization
import dev.kpetriv.sprout.feature.settings.ConfirmationOutcome
import dev.kpetriv.sprout.feature.settings.ConfirmationPurpose
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.GeneralSecurityException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parent confirmation's policy (audit 2 I1, D-M7-74): the strong-biometric authorisation first; the parent PIN
 * where that is unavailable or the key changed; a dismissed prompt is CANCELLED; no prompt possible, or any other
 * failure, is FAILED and never CONFIRMED.
 */
class PromptParentConfirmationTest {
    private val signed = mutableListOf<ByteArray>()
    private val pinRequests = mutableListOf<ConfirmationPurpose>()
    private var pinAnswer = ConfirmationOutcome.CONFIRMED

    /** A prompt-authenticated authorisation that records what it signs. */
    private val strong = ParentAuthorization { payload ->
        signed += payload
        byteArrayOf(1)
    }

    private fun confirmation(authorize: suspend (ConfirmationPurpose) -> ParentAuthorization?) =
        PromptParentConfirmation(
            authorize = authorize,
            pinFallback = { purpose ->
                pinRequests += purpose
                pinAnswer
            },
        )

    @Test
    fun `the biometric path signs a fresh, purpose-bound challenge and confirms without the PIN`() = runTest {
        val confirmation = confirmation { strong }

        assertEquals(ConfirmationOutcome.CONFIRMED, confirmation.confirm(ConfirmationPurpose.EXPORT_HOUSEHOLD))
        assertEquals(ConfirmationOutcome.CONFIRMED, confirmation.confirm(ConfirmationPurpose.EXPORT_HOUSEHOLD))

        val prefix = "sprout-parent-confirmation-v1:EXPORT_HOUSEHOLD:".encodeToByteArray()
        signed.forEach { payload ->
            assertTrue(payload.copyOf(prefix.size).contentEquals(prefix), "domain-separated and bound to the purpose")
            assertEquals(prefix.size + NONCE_SIZE, payload.size)
        }
        assertFalse(signed[0].contentEquals(signed[1]), "a fresh nonce every time")
        assertTrue(pinRequests.isEmpty())
    }

    @Test
    fun `without a strong biometric or a registered key the parent PIN decides`() = runTest {
        val unavailable = UnavailableParentAuthorization(ParentAuthAvailability.NOT_ENROLLED)
        val notRegistered =
            UnavailableParentAuthorization(ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE, keyRegistered = false)

        assertEquals(ConfirmationOutcome.CONFIRMED, confirmation { unavailable }.confirm(PURPOSE))
        pinAnswer = ConfirmationOutcome.CANCELLED
        assertEquals(ConfirmationOutcome.CANCELLED, confirmation { notRegistered }.confirm(PURPOSE))
        pinAnswer = ConfirmationOutcome.FAILED
        assertEquals(ConfirmationOutcome.FAILED, confirmation { unavailable }.confirm(PURPOSE))
        assertEquals(listOf(PURPOSE, PURPOSE, PURPOSE), pinRequests)
    }

    @Test
    fun `a changed or mismatched key falls back to the PIN, from the prompt or the signature`() = runTest {
        assertEquals(
            ConfirmationOutcome.CONFIRMED,
            confirmation { throw ParentKeyInvalidatedException() }.confirm(PURPOSE),
        )
        assertEquals(
            ConfirmationOutcome.CONFIRMED,
            confirmation { ParentAuthorization { throw ParentKeyMismatchException() } }.confirm(PURPOSE),
        )
        assertEquals(2, pinRequests.size)
    }

    @Test
    fun `a dismissed prompt is cancelled and never asks for the PIN`() = runTest {
        val outcome = confirmation { throw BiometricCancelledException(errorCode = 10) }.confirm(PURPOSE)

        assertEquals(ConfirmationOutcome.CANCELLED, outcome)
        assertTrue(pinRequests.isEmpty())
    }

    @Test
    fun `no prompt possible, or any other failure, is FAILED and never CONFIRMED`() = runTest {
        // No host activity to show the prompt over.
        assertEquals(ConfirmationOutcome.FAILED, confirmation { null }.confirm(PURPOSE))
        // The prompt or the signature fails otherwise.
        assertEquals(ConfirmationOutcome.FAILED, confirmation { error("no prompt") }.confirm(PURPOSE))
        assertEquals(
            ConfirmationOutcome.FAILED,
            confirmation { ParentAuthorization { throw GeneralSecurityException("keystore") } }.confirm(PURPOSE),
        )
        assertTrue(pinRequests.isEmpty())

        // The PIN fallback without a hosted dialog (outside the parent area) fails at once too.
        val noDialog = PinConfirmationHost(TestScope()) { error("never asked") }
        val pinOnly = PromptParentConfirmation(
            authorize = { UnavailableParentAuthorization(ParentAuthAvailability.NOT_ENROLLED) },
            pinFallback = noDialog::confirm,
        )
        assertEquals(ConfirmationOutcome.FAILED, pinOnly.confirm(PURPOSE))
    }

    private companion object {
        val PURPOSE = ConfirmationPurpose.DELETE_CHAT_HISTORY
        const val NONCE_SIZE = 32
    }
}
