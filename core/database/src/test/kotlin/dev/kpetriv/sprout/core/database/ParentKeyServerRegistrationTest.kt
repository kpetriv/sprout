package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainledger.JvmSha256Hasher
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.HouseholdId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D-M3-39: a locally registered parent key the household's server never recorded is "pending". */
@RunWith(RobolectricTestRunner::class)
class ParentKeyServerRegistrationTest {
    private val households = mutableListOf<TestHousehold>()

    private fun household(real: Boolean = true): TestHousehold {
        val clock = testClock()
        val household = if (real) {
            TestHousehold(
                RuntimeEnvironment.getApplication(),
                clock,
                sessionFor = TestHousehold.parentSession(HouseholdId("home"), "parent-uid"),
            )
        } else {
            TestHousehold(RuntimeEnvironment.getApplication(), clock)
        }
        return household.also { households += it }
    }

    @After
    fun close() = households.forEach(TestHousehold::close)

    private suspend fun TestHousehold.pending(): Boolean = policies.observeServerRegistrationPending().first()

    private fun fingerprint(key: ByteArray) = JvmSha256Hasher.sha256(key).toHex()

    @Test
    fun aRealHouseholdKeyIsPendingUntilTheServerRegistrationIsRecorded() = runTest {
        val household = household()
        household.policies.ensure(household.gbp)
        assertFalse(household.pending(), "no key, nothing pending")

        val key = household.parentAuthorizer.createParentKey().encoded
        household.policies.registerParentKey(key)
        assertTrue(household.pending())

        assertTrue(household.policies.recordServerRegisteredKey(fingerprint(key), validFromLamport = 5))
        assertFalse(household.pending())
    }

    @Test
    fun aRenewedKeyIsPendingAgainEvenWhenTheOldOneWasRecorded() = runTest {
        val household = household()
        val first = household.parentAuthorizer.createParentKey().encoded
        household.policies.registerParentKey(first)
        household.policies.recordServerRegisteredKey(fingerprint(first), validFromLamport = 2)
        assertFalse(household.pending())

        household.parentAuthorizer.resetParentKey()
        val second = household.parentAuthorizer.createParentKey().encoded
        household.policies.registerParentKey(second)

        assertTrue(household.pending(), "the household knows the old key, not the new one")
        household.policies.recordServerRegisteredKey(fingerprint(second), validFromLamport = 9)
        assertFalse(household.pending())
    }

    @Test
    fun theTrialHouseholdIsNeverPending() = runTest {
        val household = household(real = false)
        household.policies.registerParentKey(household.parentAuthorizer.createParentKey().encoded)

        assertFalse(household.pending())
    }

    @Test
    fun aRecordForAnUnknownFingerprintChangesNothing() = runTest {
        val household = household()
        household.policies.registerParentKey(household.parentAuthorizer.createParentKey().encoded)

        assertFalse(household.policies.recordServerRegisteredKey("0".repeat(64), validFromLamport = 3))

        assertTrue(household.pending())
    }
}
