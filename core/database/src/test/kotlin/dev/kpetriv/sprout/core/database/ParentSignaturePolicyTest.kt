package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.FakeParentAuthorizer
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.crypto.ParentAuthUnavailableException
import dev.kpetriv.sprout.core.crypto.ParentSigner
import dev.kpetriv.sprout.core.crypto.verifyParentSignature
import dev.kpetriv.sprout.core.domainledger.AllowancePaid
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.GamePlayed
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.domainledger.parentSignatureBytes
import dev.kpetriv.sprout.core.domainledger.parentSignedPayload
import dev.kpetriv.sprout.core.model.CalendarDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SignatureException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ParentSignaturePolicyTest {
    private val household = testHousehold()

    @After
    fun close() = household.close()

    private fun allowance(week: Int) = { envelope: dev.kpetriv.sprout.core.domainledger.Envelope ->
        AllowancePaid(envelope, household.gbp, Money(100), "weekly", "2026-W$week")
    }

    @Test
    fun unsignedParentEventsAreAcceptedBeforeTheKeyAndRejectedAfterIt() = runTest {
        val (kid, accounts) = household.addKid()
        val main = with(household) { accounts.money() }
        val writer = household.writer

        // No parent key yet: an unsigned parent event is fine (M1 behaviour).
        assertEquals(AppendResult.Accepted, writer.append(kid.id, main.id, allowance(44)))

        // Lamports 1 to 5: the kid and its three accounts (documents) and the allowance; registering creates the
        // household policy document (6), so the requirement starts at 7.
        assertEquals(7L, household.registerParentKey().parentSignatureFromLamport)

        val unsigned = assertIs<AppendResult.Rejected>(writer.append(kid.id, main.id, allowance(45)))
        assertEquals(RoomLedgerRepository.INVARIANT_REJECTION, unsigned.reason)
        assertEquals(listOf(Violation.ParentSignatureMissing("AllowancePaid")), unsigned.violations)

        assertEquals(
            AppendResult.Accepted,
            writer.appendAsParent(kid.id, main.id, household.authorization(), allowance(45)),
        )
        assertEquals(1, household.parentAuthorizer.signaturesIssued)
        val signed = household.ledger.observeEvents(kid.id, limit = 1).first().single()
        val signature = assertNotNull(signed.parentSignatureBytes())
        val publicKey = assertNotNull(household.parentAuthorizer.existingParentPublicKey())
        assertTrue(verifyParentSignature(publicKey, signed.parentSignedPayload(), signature))
        assertEquals(signed.envelope.parentSignature, household.database.ledgerEventDao().all().last().parentSignature)

        // Events that are not parent-only still need no signature.
        val game = writer.append(kid.id, main.id) {
            GamePlayed(it, household.gbp, Money(5), "memory.sprout", 6, CalendarDate(2026, 11, 2))
        }
        assertEquals(AppendResult.Accepted, game)
        // The unsigned allowance from before the key still counts.
        assertEquals(
            Money(205),
            household.ledger.observeBalances(kid.id).first().single {
                it.accountId == main.id
            }.amount,
        )
        assertEquals(emptyList(), household.ledger.observeAnomalies(kid.id).first())
    }

    @Test
    fun aParentSignatureByAnotherKeyIsRejected() = runTest {
        val (kid, accounts) = household.addKid()
        val main = with(household) { accounts.money() }
        household.registerParentKey()
        val stranger = FakeParentAuthorizer().also { it.createParentKey() }
        val forged = ParentAuthorizations(ParentSigner()).from(stranger.initSignature())

        val rejected =
            assertIs<AppendResult.Rejected>(household.writer.appendAsParent(kid.id, main.id, forged, allowance(45)))
        assertEquals(RoomLedgerRepository.PARENT_SIGNATURE_REJECTION, rejected.reason)
        assertEquals(0, household.database.ledgerEventDao().count())
    }

    @Test
    fun anAuthorizationSignsExactlyOneEvent() = runTest {
        val (kid, accounts) = household.addKid()
        val main = with(household) { accounts.money() }
        household.registerParentKey()
        val authorization = household.authorization()

        assertEquals(
            AppendResult.Accepted,
            household.writer.appendAsParent(kid.id, main.id, authorization, allowance(45)),
        )
        assertFailsWith<SignatureException> {
            household.writer.appendAsParent(kid.id, main.id, authorization, allowance(46))
        }
        assertEquals(1, household.database.ledgerEventDao().count())
    }

    @Test
    fun withoutAStrongBiometricParentSignaturesStayOff() = runTest {
        val noBiometric = FakeParentAuthorizer(availability = ParentAuthAvailability.NOT_ENROLLED)
        // Enrolment cannot create a key without a strong biometric, so it only records the household policy.
        assertFailsWith<ParentAuthUnavailableException> { noBiometric.createParentKey() }
        val policy = household.policies.ensure(household.gbp)

        assertFalse(policy.requireParentSignature)
        assertTrue(policy.parentSignaturesOff)
        val (kid, accounts) = household.addKid()
        val main = with(household) { accounts.money() }
        assertEquals(AppendResult.Accepted, household.writer.append(kid.id, main.id, allowance(45)))
    }

    @Test
    fun onlyAnEcP256KeyCanBeRegistered() = runTest {
        assertFailsWith<IllegalArgumentException> { household.registerKey.register(byteArrayOf(1, 2, 3)) }
        assertEquals(null, household.policies.get())
    }
}
