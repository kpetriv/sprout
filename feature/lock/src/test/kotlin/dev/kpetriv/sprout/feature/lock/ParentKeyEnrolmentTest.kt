package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.core.crypto.FakeParentAuthorizer
import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.PinLockoutPolicy
import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.PIN
import dev.kpetriv.sprout.feature.lock.SessionFixture.Companion.WRONG_PIN
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.security.Signature
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ParentKeyEnrolmentTest {
    private fun test(block: suspend TestScope.() -> Unit) = runTest(UnconfinedTestDispatcher()) { block() }

    /** An unlocked session with a PIN, as in Settings. */
    private suspend fun TestScope.unlocked(): SessionFixture = SessionFixture(backgroundScope, pin = PIN).also {
        assertEquals(PinUnlockResult.Unlocked, it.session.unlockWithPin(PIN.toCharArray()))
    }

    @Test
    fun `enrolment with the right PIN creates, proves and registers a key`() = test {
        val f = unlocked()
        val pin = PIN.toCharArray()

        assertEquals(ParentKeyChangeResult.Registered, f.enrolment.enrol(pin))

        val registered = assertNotNull(f.registry.key)
        assertEquals(registered, f.authorizer.existingParentPublicKey())
        assertEquals(1, f.prompter.prompts)
        assertEquals(1, f.registry.registrations)
        assertTrue(pin.all { it == '\u0000' })
        assertEquals(ParentKeyStatus.READY, f.session.parentKeyStatus())
        assertIs<BiometricUnlockRequest.Ready>(f.session.unlockWithBiometric())
    }

    @Test
    fun `a background enrolment is in progress from the hand-off until it ended`() = test {
        val f = unlocked()
        val progress: ParentKeyChangeProgress = f.provisioner
        val gate = CompletableDeferred<Unit>()
        f.prompter.gate = gate
        assertFalse(progress.observeInProgress().first())

        val job = f.enrolment.enrolInBackground(PIN.toCharArray())

        // The dashboard hides its signing cards meanwhile (D-REL-12).
        assertEquals(1, f.prompter.prompts, "the proof prompt is open")
        assertTrue(progress.observeInProgress().first())
        gate.complete(Unit)
        job.join()
        assertFalse(progress.observeInProgress().first())
        assertNotNull(f.registry.key)
    }

    @Test
    fun `a failed enrolment is no longer in progress`() = test {
        val f = unlocked()
        f.prompter.answer = { throw BiometricCancelledException(errorCode = 10) }

        assertEquals(ParentKeyChangeResult.Cancelled, f.enrolment.enrol(PIN.toCharArray()))

        assertFalse(f.provisioner.observeInProgress().first())
    }

    @Test
    fun `enrolment needs the right PIN and counts a wrong one as a failed attempt`() = test {
        val f = unlocked()

        assertEquals(
            ParentKeyChangeResult.WrongPin(LockoutDecision.Allowed),
            f.enrolment.enrol(WRONG_PIN.toCharArray()),
        )

        assertEquals(1, f.lockoutStore.state?.consecutiveFailures)
        assertNull(f.registry.key)
        assertNull(f.authorizer.existingParentPublicKey())
        assertEquals(0, f.prompter.prompts)
    }

    @Test
    fun `during a lockout the PIN is not checked and nothing is created`() = test {
        val f = unlocked()
        repeat(PinLockoutPolicy.FREE_ATTEMPTS + 1) { f.enrolment.enrol(WRONG_PIN.toCharArray()) }

        val result = f.enrolment.enrol(PIN.toCharArray())

        assertEquals(ParentKeyChangeResult.LockedOut(LockoutDecision.Wait(PinLockoutPolicy.BASE_LOCKOUT)), result)
        assertEquals(0, f.authorizer.keysCreated)
    }

    @Test
    fun `enrolment refuses to replace a registered key`() = test {
        val f = unlocked()
        val existing = f.enrolKeyDirectly()

        assertEquals(ParentKeyChangeResult.AlreadyRegistered, f.enrolment.enrol(PIN.toCharArray()))

        assertEquals(existing, f.registry.key)
        assertEquals(existing, f.authorizer.existingParentPublicKey())
        assertEquals(0, f.prompter.prompts)
        // Not even a PIN attempt was spent.
        assertEquals(0, f.lockoutStore.state?.consecutiveFailures ?: 0)
    }

    @Test
    fun `without the household policy enrolment stops before the PIN, the challenge, the key and the prompt`() = test {
        val f = unlocked()
        f.registry.policyReady = false

        assertEquals(ParentKeyChangeResult.PolicyNotSynced, f.enrolment.enrol(PIN.toCharArray()))

        // D-REL-15: no fingerprint prompt for a registration that is bound to be refused.
        assertEquals(1, f.registry.policyChecks)
        assertEquals(0, f.prompter.prompts)
        assertEquals(0, f.authorizer.keysCreated)
        assertEquals(emptyList(), f.challenges.issued)
        assertEquals(0, f.lockoutStore.state?.consecutiveFailures ?: 0, "the PIN was not checked")
        assertEquals(
            ParentKeyChangeResult.PolicyNotSynced,
            f.enrolment.enrol(WRONG_PIN.toCharArray()),
            "not even a wrong PIN is counted",
        )
        assertEquals(0, f.lockoutStore.state?.consecutiveFailures ?: 0)
        assertNull(f.registry.key)

        f.registry.policyReady = true
        assertEquals(ParentKeyChangeResult.Registered, f.enrolment.enrol(PIN.toCharArray()))
    }

    @Test
    fun `a renewal does not ask for the household policy`() = test {
        val f = unlocked()
        f.enrolKeyDirectly()
        f.registry.policyReady = false

        assertEquals(ParentKeyChangeResult.Registered, f.renewal.renew(PIN.toCharArray()))
        assertEquals(0, f.registry.policyChecks)
    }

    @Test
    fun `a locked session cannot enrol`() = test {
        val f = SessionFixture(backgroundScope, pin = PIN)

        assertEquals(ParentKeyChangeResult.NotAllowed, f.enrolment.enrol(PIN.toCharArray()))
        assertEquals(0, f.authorizer.keysCreated)
    }

    @Test
    fun `without a strong biometric enrolment records the unsigned policy and creates nothing`() = test {
        val f = unlocked()
        f.authorizer.availability = ParentAuthAvailability.NOT_ENROLLED

        assertEquals(
            ParentKeyChangeResult.Unavailable(ParentAuthAvailability.NOT_ENROLLED),
            f.enrolment.enrol(PIN.toCharArray()),
        )
        assertTrue(f.registry.signingUnavailableRecorded)
        assertEquals(0, f.authorizer.keysCreated)
    }

    @Test
    fun `a cancelled proof prompt leaves no key and registers nothing`() = test {
        val f = unlocked()
        f.prompter.answer = { throw BiometricCancelledException(errorCode = 10) }

        assertEquals(ParentKeyChangeResult.Cancelled, f.enrolment.enrol(PIN.toCharArray()))

        assertNull(f.authorizer.existingParentPublicKey())
        assertNull(f.registry.key)
    }

    @Test
    fun `a proof signature from anything but the prompted operation fails`() = test {
        val f = unlocked()
        f.prompter.answer = { Signature.getInstance("SHA256withECDSA") }

        assertEquals(ParentKeyChangeResult.Failed, f.enrolment.enrol(PIN.toCharArray()))

        assertNull(f.authorizer.existingParentPublicKey())
        assertNull(f.registry.key)
    }

    @Test
    fun `a stray local key is never registered, enrolment starts from a fresh key`() = test {
        val f = unlocked()
        val stray = f.authorizer.createParentKey()

        assertEquals(ParentKeyChangeResult.Registered, f.enrolment.enrol(PIN.toCharArray()))

        assertNotEquals(stray, f.registry.key)
        assertEquals(f.registry.key, f.authorizer.existingParentPublicKey())
    }

    @Test
    fun `renewal after a fingerprint change replaces and re-registers the key`() = test {
        val f = unlocked()
        val old = f.enrolKeyDirectly()
        f.authorizer.invalidateKey()
        assertEquals(ParentKeyStatus.NEEDS_RENEWAL, f.session.parentKeyStatus())

        assertEquals(ParentKeyChangeResult.Registered, f.renewal.renew(PIN.toCharArray()))

        val renewed = assertNotNull(f.registry.key)
        assertNotEquals(old, renewed)
        assertEquals(renewed, f.authorizer.existingParentPublicKey())
        assertEquals(1, f.registry.replacements)
        assertEquals(0, f.registry.registrations)
        assertEquals(ParentKeyStatus.READY, f.session.parentKeyStatus())
    }

    @Test
    fun `renewal also repairs a local key that differs from the registered one`() = test {
        val f = unlocked()
        f.registry.key = FakeParentAuthorizer().createParentKey()

        assertEquals(ParentKeyChangeResult.Registered, f.renewal.renew(PIN.toCharArray()))
        assertEquals(f.registry.key, f.authorizer.existingParentPublicKey())
    }

    @Test
    fun `renewal needs the right PIN and keeps the registered key on failure`() = test {
        val f = unlocked()
        val old = f.enrolKeyDirectly()
        f.authorizer.invalidateKey()

        assertIs<ParentKeyChangeResult.WrongPin>(f.renewal.renew(WRONG_PIN.toCharArray()))
        assertEquals(old, f.registry.key)

        f.prompter.answer = { throw BiometricErrorException(errorCode = 7) }
        assertEquals(ParentKeyChangeResult.Failed, f.renewal.renew(PIN.toCharArray()))
        assertEquals(old, f.registry.key)
        assertEquals(0, f.registry.replacements)
        // The unproven new key was deleted; renewal is still needed.
        assertNull(f.authorizer.existingParentPublicKey())
        assertEquals(ParentKeyStatus.NEEDS_RENEWAL, f.session.parentKeyStatus())
    }

    @Test
    fun `enrolment binds the new key to a fresh server challenge and registers its chain`() = test {
        val f = unlocked()
        var keysWhenIssued = -1
        f.challenges.onIssued = { keysWhenIssued = f.authorizer.keysCreated }

        assertEquals(ParentKeyChangeResult.Registered, f.enrolment.enrol(PIN.toCharArray()))

        val challenge = f.challenges.issued.single()
        assertEquals(0, keysWhenIssued, "the challenge is fetched before the key is created")
        assertTrue(challenge.contentEquals(f.authorizer.keyChallenge), "the key is bound to that challenge")
        val chain = assertNotNull(f.registry.lastChain)
        assertEquals(2, chain.size)
        assertTrue(chain[0].contentEquals(FakeParentAuthorizer.FAKE_LEAF_PREFIX + challenge), "leaf first")
    }

    @Test
    fun `without a household session the key is unattested and the chain empty`() = test {
        val f = unlocked()
        f.challenges.next = null

        assertEquals(ParentKeyChangeResult.Registered, f.enrolment.enrol(PIN.toCharArray()))

        assertNull(f.authorizer.keyChallenge)
        assertEquals(emptyList(), f.registry.lastChain)
    }

    @Test
    fun `a Keystore that cannot attest registers the key with an empty chain`() = test {
        val f = unlocked()
        f.authorizer.canAttest = false

        assertEquals(ParentKeyChangeResult.Registered, f.enrolment.enrol(PIN.toCharArray()))

        assertEquals(emptyList(), f.registry.lastChain)
        assertEquals(1, f.registry.registrations)
    }

    @Test
    fun `a challenge that cannot be fetched changes nothing, not even a working key`() = test {
        val f = unlocked()
        val working = f.enrolKeyDirectly()
        f.challenges.failure = java.io.IOException("offline")

        assertEquals(ParentKeyChangeResult.Failed, f.renewal.renew(PIN.toCharArray()))

        assertEquals(working, f.authorizer.existingParentPublicKey())
        assertEquals(working, f.registry.key)
        assertEquals(0, f.prompter.prompts)
        assertEquals(ParentKeyStatus.READY, f.session.parentKeyStatus())
    }

    @Test
    fun `renewal sends a new key with a chain for a new challenge`() = test {
        val f = unlocked()
        f.enrolment.enrol(PIN.toCharArray())
        val first = f.challenges.issued.single()
        f.challenges.next = ByteArray(32) { 42 }

        assertEquals(ParentKeyChangeResult.Registered, f.renewal.renew(PIN.toCharArray()))

        val second = f.challenges.issued.last()
        assertFalse(first.contentEquals(second))
        assertTrue(assertNotNull(f.registry.lastChain)[0].contentEquals(FakeParentAuthorizer.FAKE_LEAF_PREFIX + second))
        assertEquals(1, f.registry.replacements)
    }

    @Test
    fun `renewal without a registered key is refused`() = test {
        val f = unlocked()

        assertEquals(ParentKeyChangeResult.NotRegistered, f.renewal.renew(PIN.toCharArray()))
        assertEquals(0, f.authorizer.keysCreated)
    }

    @Test
    fun `background enrolment survives the caller and zeroes the PIN`() = test {
        val f = unlocked()
        val pin = PIN.toCharArray()

        f.enrolment.enrolInBackground(pin).join()

        assertNotNull(f.registry.key)
        assertTrue(pin.all { it == '\u0000' })
        assertFalse(f.registry.signingUnavailableRecorded)
    }
}
