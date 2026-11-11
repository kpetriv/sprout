package dev.kpetriv.sprout.settings

import dev.kpetriv.sprout.FakeSessionStore
import dev.kpetriv.sprout.core.crypto.FakeParentAuthorizer
import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import dev.kpetriv.sprout.core.database.HouseholdPolicy
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.usecase.RegisterParentKeyUseCase
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.feature.lock.ChangePinResult
import dev.kpetriv.sprout.feature.lock.ParentKeyChangeResult
import dev.kpetriv.sprout.feature.lock.PinRuleViolation
import dev.kpetriv.sprout.feature.settings.NewPinProblem
import dev.kpetriv.sprout.feature.settings.PinChangeOutcome
import dev.kpetriv.sprout.feature.settings.SigningKeyOutcome
import dev.kpetriv.sprout.parentSession
import dev.kpetriv.sprout.trialSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SettingsAdaptersTest {
    private val gbp = Currency.Fiat("GBP")
    private val authorizer = FakeParentAuthorizer()
    private val policies = InMemoryPolicies(gbp)
    private val sessions = FakeSessionStore(trialSession())
    private val published = mutableListOf<ByteArray>()
    private val chains = mutableListOf<List<ByteArray>>()
    private var backendResult: Result<Unit> = Result.success(Unit)
    private val registry = HouseholdParentKeyRegistry(
        policies = policies,
        useCase = RegisterParentKeyUseCase(policies),
        currency = { gbp },
        sessions = sessions,
        registrar = { key, chain ->
            published += key
            chains += chain
            backendResult
        },
    )

    @Test
    fun `a real household's parent key is also registered with the backend, the trial's never`() = runTest {
        registry.registerParentKey(authorizer.createParentKey(), emptyList())
        assertEquals(emptyList(), published, "the trial household has no backend")

        policies.policy.value = null
        sessions.session.value = parentSession()
        authorizer.resetParentKey()
        val key = authorizer.createParentKey(ByteArray(32) { 3 })
        val chain = authorizer.parentKeyAttestationChain()
        registry.registerParentKey(key, chain)
        assertEquals(listOf(key.encoded.toList()), published.map { it.toList() })
        assertEquals(listOf(chain.map { it.toList() }), chains.map { sent -> sent.map { it.toList() } })
    }

    @Test
    fun `a renewal sends the new key's chain, and a backend failure keeps the local registration`() = runTest {
        sessions.session.value = parentSession()
        val first = authorizer.createParentKey(ByteArray(32) { 1 })
        registry.registerParentKey(first, authorizer.parentKeyAttestationChain())
        authorizer.resetParentKey()
        val renewed = authorizer.createParentKey(ByteArray(32) { 2 })
        backendResult = Result.failure(IllegalStateException("offline"))

        registry.replaceParentKey(renewed, authorizer.parentKeyAttestationChain())

        assertEquals(renewed, registry.registeredParentKey(), "the PIN and fingerprint proved it; it stays")
        assertTrue(chains.last()[0].contentEquals(FakeParentAuthorizer.FAKE_LEAF_PREFIX + ByteArray(32) { 2 }))
    }

    @Test
    fun `the registry reads, registers and replaces the household's parent key`() = runTest {
        assertNull(registry.registeredParentKey())

        val first = authorizer.createParentKey()
        registry.registerParentKey(first, emptyList())
        assertEquals(first, registry.registeredParentKey())
        assertTrue(policies.policy.value!!.requireParentSignature)
        // Registering over an existing key is refused: only renewal replaces it.
        assertFailsWith<IllegalStateException> { registry.registerParentKey(first, emptyList()) }

        authorizer.resetParentKey()
        val second = authorizer.createParentKey()
        registry.replaceParentKey(second, emptyList())
        assertEquals(second, registry.registeredParentKey())
    }

    @Test
    fun `replacing without a registered key is refused`() = runTest {
        assertFailsWith<IllegalStateException> { registry.replaceParentKey(authorizer.createParentKey(), emptyList()) }
        assertNull(policies.policy.value)
    }

    @Test
    fun `a key that is not EC P-256 is never registered`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            registry.registerParentKey(PublicKeyBytes(byteArrayOf(1, 2, 3)), emptyList())
        }
        assertNull(policies.policy.value)
    }

    @Test
    fun `recording the unsigned state creates the policy with signatures off`() = runTest {
        registry.recordSigningUnavailable()
        assertFalse(policies.policy.value!!.requireParentSignature)
        assertNull(registry.registeredParentKey())
    }

    @Test
    fun `a real household's phone never creates the policy when signing is unavailable`() = runTest {
        sessions.session.value = parentSession()

        registry.recordSigningUnavailable()

        // A restored phone before its first pull: the household's policy (its currency) comes from the pull (D-REL-11).
        assertNull(policies.policy.value)
    }

    @Test
    fun `key change results map onto the settings outcomes`() {
        val wait = LockoutDecision.Wait(30.seconds)
        assertEquals(SigningKeyOutcome.Registered, ParentKeyChangeResult.Registered.toOutcome())
        assertEquals(
            SigningKeyOutcome.Unavailable(ParentAuthAvailability.NO_HARDWARE),
            ParentKeyChangeResult.Unavailable(ParentAuthAvailability.NO_HARDWARE).toOutcome(),
        )
        assertEquals(SigningKeyOutcome.AlreadyOn, ParentKeyChangeResult.AlreadyRegistered.toOutcome())
        assertEquals(SigningKeyOutcome.NotRegistered, ParentKeyChangeResult.NotRegistered.toOutcome())
        assertEquals(SigningKeyOutcome.WrongPin(wait), ParentKeyChangeResult.WrongPin(wait).toOutcome())
        assertEquals(SigningKeyOutcome.LockedOut(wait), ParentKeyChangeResult.LockedOut(wait).toOutcome())
        assertEquals(SigningKeyOutcome.Cancelled, ParentKeyChangeResult.Cancelled.toOutcome())
        assertEquals(SigningKeyOutcome.Failed, ParentKeyChangeResult.NotAllowed.toOutcome())
        assertEquals(SigningKeyOutcome.Failed, ParentKeyChangeResult.Failed.toOutcome())
    }

    @Test
    fun `pin change results map onto the settings outcomes`() {
        val wait = LockoutDecision.Wait(30.seconds)
        assertEquals(PinChangeOutcome.Changed, ChangePinResult.Changed.toOutcome())
        assertEquals(PinChangeOutcome.WrongCurrentPin(wait), ChangePinResult.WrongCurrentPin(wait).toOutcome())
        assertEquals(PinChangeOutcome.LockedOut(wait), ChangePinResult.LockedOut(wait).toOutcome())
        assertEquals(
            PinChangeOutcome.Invalid(NewPinProblem.SEQUENCE),
            ChangePinResult.Invalid(PinRuleViolation.SEQUENCE).toOutcome(),
        )
        assertEquals(PinChangeOutcome.Mismatch, ChangePinResult.Mismatch.toOutcome())
        assertEquals(PinChangeOutcome.NotAllowed, ChangePinResult.NotAllowed.toOutcome())
        assertEquals(PinChangeOutcome.Failed, ChangePinResult.Failed.toOutcome())
        assertEquals(PinRuleViolation.entries.map { it.name }, NewPinProblem.entries.map { it.name })
    }
}

/** The household policy in memory; no policy until [ensure] or [registerParentKey]. */
private class InMemoryPolicies(private val currency: Currency.Fiat) : HouseholdPolicyRepository {
    val policy = MutableStateFlow<HouseholdPolicy?>(null)

    override fun observe(): Flow<HouseholdPolicy?> = policy

    override suspend fun get(): HouseholdPolicy? = policy.value

    override suspend fun ensure(fiatCurrency: Currency.Fiat): HouseholdPolicy =
        policy.value ?: HouseholdPolicy(false, null, null, fiatCurrency, Instant.fromEpochMilliseconds(0)).also {
            policy.value = it
        }

    override suspend fun registerParentKey(publicKeyDer: ByteArray): HouseholdPolicy =
        HouseholdPolicy(true, 1, publicKeyDer, currency, Instant.fromEpochMilliseconds(0)).also { policy.value = it }
}
