package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore
import dev.kpetriv.sprout.core.crypto.JceEcdsaSigner
import dev.kpetriv.sprout.core.crypto.JceEcdsaVerifier
import dev.kpetriv.sprout.core.crypto.generateSoftwareSigningKey
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.ChoreRewarded
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.Fixtures
import dev.kpetriv.sprout.core.domainledger.LamportClock
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerEventFactory
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.signWith
import dev.kpetriv.sprout.core.domainledger.signatureBytes
import dev.kpetriv.sprout.core.domainledger.signedPayload
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.domainledger.withSignature
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The production local-origin rule: [RoomLedgerRepository.append] takes only this device's own, signed events. */
@RunWith(RobolectricTestRunner::class)
class LocalEventCheckTest {
    private val clock = testClock()
    private val database = inMemoryDatabase()
    private val keyStore = InMemoryDeviceKeyStore(clock)
    private val signer = DeviceSigner(keyStore, Dispatchers.Unconfined)
    private val repository = RoomLedgerRepository(database, clock, signer, JceEcdsaVerifier())
    private val localDevice = DeviceId(keyStore.publicKey().fingerprint().toHex())
    private val localAuthor = EventAuthor(Fixtures.household, Fixtures.parentPhone.uid, Role.PARENT, localDevice)

    @After
    fun close() = database.close()

    private fun chore(author: EventAuthor = localAuthor, amount: Long = 100, lamport: Long = 0): LedgerEvent =
        LedgerEventFactory(clock, LamportClock(start = lamport), Fixtures.seededIds(lamport.toInt()))
            .create(author, Fixtures.kid, Fixtures.main.id, headHash = null) {
                ChoreRewarded(it, Fixtures.gbp, Money(amount), "c")
            }

    private suspend fun signedLocally(event: LedgerEvent) = event.withSignature(signer.sign(event.signedPayload()))

    private suspend fun assertRejectedAndNothingWritten(event: LedgerEvent) {
        val rejected = assertIs<AppendResult.Rejected>(repository.append(event))
        assertEquals(RoomLedgerRepository.FOREIGN_REJECTION, rejected.reason)
        assertEquals(0, database.ledgerEventDao().count())
        assertEquals(emptyList(), database.outboxDao().all())
        assertEquals(emptyList(), database.auditLogDao().all())
    }

    @Test
    fun aLocalEventSignedByThisDeviceIsAccepted() = runTest {
        database.register(Fixtures.accounts)
        assertEquals(AppendResult.Accepted, repository.append(signedLocally(chore())))
        // Reading it back verifies the stored signature too.
        assertEquals(0, repository.quarantineUnverifiable())
        assertEquals(listOf(Money(100)), repository.observeBalances(Fixtures.kid).first().map { it.amount })
    }

    @Test
    fun anUnsignedLocalEventIsRejected() = runTest {
        database.register(Fixtures.accounts)
        assertRejectedAndNothingWritten(chore())
    }

    @Test
    fun aSignatureOverDifferentContentIsRejected() = runTest {
        database.register(Fixtures.accounts)
        val other = signedLocally(chore(amount = 999))
        assertRejectedAndNothingWritten(chore().withSignature(assertNotNull(other.signatureBytes())))
    }

    @Test
    fun anEventFromAnotherDeviceIsRejectedEvenIfItsOwnKeySignedIt() = runTest {
        database.register(Fixtures.accounts)
        val foreignKey = generateSoftwareSigningKey()
        val foreignDevice = DeviceId(foreignKey.publicKey.fingerprint().toHex())
        val foreign = chore(author = localAuthor.copy(deviceId = foreignDevice))
        assertRejectedAndNothingWritten(foreign.signWith(JceEcdsaSigner(foreignKey)::sign))
    }

    @Test
    fun anEventClaimingThisDeviceButSignedByAnotherKeyIsRejected() = runTest {
        database.register(Fixtures.accounts)
        val impostor = JceEcdsaSigner(generateSoftwareSigningKey())
        assertRejectedAndNothingWritten(chore().signWith(impostor::sign))
    }

    @Test
    fun aStoredLocalRowWhoseSignatureNoLongerVerifiesIsQuarantinedOnLoad() = runTest {
        database.register(Fixtures.accounts)
        val event = signedLocally(chore())
        assertEquals(AppendResult.Accepted, repository.append(event))

        // The signature is not part of the hash, so swapping it keeps the hash valid; only the signature check sees it.
        val forged = chore().signWith(JceEcdsaSigner(generateSoftwareSigningKey())::sign)
        val row = database.ledgerEventDao().all().single()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE ledger_events SET payloadJson = ? WHERE eventId = ?",
            arrayOf(
                row.payloadJson.replace(event.envelope.signature!!, forged.envelope.signature!!),
                event.eventId.value,
            ),
        )

        assertEquals(1, repository.quarantineUnverifiable())
        val stored = database.ledgerEventDao().all().single()
        assertTrue(stored.quarantined)
        assertEquals(LedgerEventMapper.SIGNATURE_INVALID, stored.quarantineReason)
        assertEquals(emptyList(), repository.observeBalances(Fixtures.kid).first())
    }
}
