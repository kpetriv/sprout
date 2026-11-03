package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.core.crypto.FakeParentAuthorizer
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.ParentAuthUnavailableException
import dev.kpetriv.sprout.core.crypto.ParentKeyInvalidatedException
import dev.kpetriv.sprout.core.crypto.ParentKeyMismatchException
import dev.kpetriv.sprout.core.crypto.ParentKeyNotRegisteredException
import dev.kpetriv.sprout.core.crypto.ParentSigner
import dev.kpetriv.sprout.core.crypto.verifyParentSignature
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ParentAuthorizationPromptTest {
    private val authorizer = FakeParentAuthorizer()
    private val registry = FakeParentKeyRegistry()
    private val prompt = ParentAuthorizationPrompt(authorizer, registry, ParentSigner())
    private val payload = "sprout/ledger-event-parent/v2\u0000event".encodeToByteArray()

    private suspend fun enrol() = authorizer.createParentKey().also { registry.key = it }

    @Test
    fun `a successful prompt yields an authorisation that signs exactly once`() = runTest {
        val registered = enrol()
        var prompts = 0
        val authorization = prompt.authorizeWith { signature ->
            prompts++
            signature
        }

        val signature = authorization.sign(payload)

        assertEquals(1, prompts)
        assertTrue(verifyParentSignature(registered, payload, signature))
        assertFailsWith<IllegalStateException> { authorization.sign(payload) }
    }

    @Test
    fun `without a strong biometric no prompt is shown and signing throws ParentAuthUnavailable`() = runTest {
        authorizer.availability = ParentAuthAvailability.NO_HARDWARE

        val authorization = prompt.authorizeWith { fail("No prompt without a strong biometric") }

        val unavailable = assertIs<UnavailableParentAuthorization>(authorization)
        assertEquals(ParentAuthAvailability.NO_HARDWARE, unavailable.availability)
        val error = assertFailsWith<ParentAuthUnavailableException> { authorization.sign(payload) }
        assertEquals(ParentAuthAvailability.NO_HARDWARE, error.availability)
    }

    @Test
    fun `without a registered key nothing is created or prompted, and signing reports not registered`() = runTest {
        val authorization = prompt.authorizeWith { fail("No prompt without a registered key") }

        val unavailable = assertIs<UnavailableParentAuthorization>(authorization)
        assertFalse(unavailable.keyRegistered)
        // Still a ParentAuthUnavailableException, so the D-M2-1 fallback applies.
        assertFailsWith<ParentKeyNotRegisteredException> { authorization.sign(payload) }
        assertNull(authorizer.existingParentPublicKey())
        assertEquals(0, authorizer.keysCreated)
    }

    @Test
    fun `a local key that differs from the registered key fails closed before any prompt`() = runTest {
        enrol()
        registry.key = FakeParentAuthorizer().createParentKey()

        assertFailsWith<ParentKeyMismatchException> { prompt.authorizeWith { fail("No prompt") } }
        assertEquals(0, authorizer.signaturesIssued)
    }

    @Test
    fun `a registered key without a local key fails closed and creates nothing`() = runTest {
        registry.key = FakeParentAuthorizer().createParentKey()

        val error = assertFailsWith<ParentKeyMismatchException> { prompt.authorizeWith { fail("No prompt") } }
        // Same recovery as an invalidated key: renew it.
        assertIs<ParentKeyInvalidatedException>(error)
        assertEquals(0, authorizer.keysCreated)
    }

    @Test
    fun `an invalidated key surfaces before any prompt`() = runTest {
        enrol()
        authorizer.invalidateKey()

        assertFailsWith<ParentKeyInvalidatedException> { prompt.authorizeWith { fail("No prompt") } }
    }

    @Test
    fun `a signature that does not verify against the registered key is never returned`() = runTest {
        enrol()
        val authorization = prompt.authorizeWith { it }
        // The key is replaced on this device after the prompt (it can only happen through renewal, but fail closed).
        registry.key = FakeParentAuthorizer().createParentKey()
        val stale = SingleUseParentAuthorization(authorizer.initSignature(), ParentSigner(), registry.key!!)

        assertFailsWith<ParentKeyMismatchException> { stale.sign(payload) }
        // The one made before the change was bound to the key registered then.
        authorization.sign(payload)
    }

    @Test
    fun `a cancelled prompt propagates`() = runTest {
        enrol()
        assertFailsWith<BiometricCancelledException> {
            prompt.authorizeWith { throw BiometricCancelledException(errorCode = 10) }
        }
    }
}
