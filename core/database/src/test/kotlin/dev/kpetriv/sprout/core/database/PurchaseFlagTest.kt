package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.JceEcdsaVerifier
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.PurchaseFlagReason
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Envelope
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.LedgerEvent
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.ManualAdjustment
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.domainledger.TimeBankCredited
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.sync.DocTypes
import dev.kpetriv.sprout.core.sync.DocumentEnvelope
import dev.kpetriv.sprout.core.sync.EventApplyReport
import dev.kpetriv.sprout.core.sync.PulledEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Receiver-side flags on pulled kid purchases (D-REL-65): a PARENT device flags a kid purchase that matches no reward
 * version it has seen, and nothing else changes. [receiver] is the PARENT device under test, [editor] a second parent
 * device whose reward versions it pulls, [kid] the kid device that signs the purchases.
 */
@RunWith(RobolectricTestRunner::class)
class PurchaseFlagTest {
    private val receiver = parentDevice()
    private val editor = parentDevice()
    private val kid = kidDevice()
    private val others = mutableListOf<TestHousehold>()

    private val screen = Reward(
        RewardId("screen"),
        HOME,
        "30 minutes",
        RewardKind.SCREEN_TIME,
        Money(100),
        Currency.Fiat("GBP"),
        minutesGranted = 30,
    )
    private val raised = screen.copy(cost = Money(200))

    /** After the history start of every receiver (all recorded at their clock's start). */
    private val after = TestHousehold.DEFAULT_START + 1.hours

    /** Before it: a purchase the household made before this device's history (a restore). */
    private val before = TestHousehold.DEFAULT_START - 1.hours

    private lateinit var main: Account
    private lateinit var minutes: Account

    /** The kid device's chain head as the crafted events extend it (they are never stored on the kid device). */
    private var head: String? = null

    @After
    fun close() {
        (listOf(receiver, editor, kid) + others).forEach(TestHousehold::close)
    }

    /** Every device registered, [KID_X] with its accounts on [receiver], and 500 in its money account. */
    private suspend fun linked() {
        registerEachOther(receiver, editor, kid)
        val (_, accounts) = receiver.addKid(KID_X.value)
        receiver.registerParentKey()
        main = with(receiver) { accounts.money() }
        minutes = with(receiver) { accounts.minutes() }
        val funding = receiver.writer.appendAsParent(KID_X, main.id, receiver.authorization()) {
            ManualAdjustment(it, receiver.gbp, Money(500), "start")
        }
        assertEquals(AppendResult.Accepted, funding)
    }

    /** [reward] at [lamport] as [this] parent device signs it (a tombstone when [deleted]). */
    private suspend fun TestHousehold.version(
        reward: Reward,
        lamport: Long,
        deleted: Boolean = false,
        payload: JsonObject = DocumentPayloads.encode(Reward.serializer(), reward),
    ): DocumentEnvelope {
        val unsigned = DocumentEnvelope(
            householdId = HOME,
            docType = DocTypes.REWARD,
            docId = reward.id.value,
            authorUid = session.uid,
            authorDeviceId = deviceId,
            lamport = lamport,
            deleted = deleted,
            payload = payload,
        )
        return unsigned.copy(signature = Base64.Default.encode(signer.sign(unsigned.signedPayload())))
    }

    /** The next event of [kid]'s chain at [lamport], signed by the kid device. */
    private suspend fun kidEvent(
        account: Account,
        lamport: Long,
        eventId: EventId? = null,
        body: (Envelope) -> LedgerEvent,
    ): JsonObject {
        val json = kid.craft(KID_X, account.id, lamport = lamport, eventId = eventId, prevHash = head, body = body)
        head = assertNotNull(LedgerJson.decodeOrNull(json)).envelope.hash
        return json
    }

    private suspend fun purchase(
        lamport: Long,
        amount: Long,
        rewardId: String = screen.id.value,
        minutes: Long? = 30,
        eventId: EventId? = null,
    ): JsonObject = kidEvent(main, lamport, eventId) {
        RewardPurchased(it, kid.gbp, Money(amount), rewardId, minutes?.let(::Money))
    }

    private fun idOf(json: JsonObject): EventId = assertNotNull(LedgerJson.decodeOrNull(json)).eventId

    private suspend fun TestHousehold.ingest(vararg events: JsonObject, stamp: kotlin.time.Instant? = after) =
        applier.applyEvents(events.toList(), stamp)

    private suspend fun TestHousehold.flags() = database.purchaseFlagDao().all()

    private suspend fun TestHousehold.balance(currency: Currency): Money =
        ledger.observeBalances(KID_X).first().filter { it.currency == currency }.sumOf { it.amount.minorUnits }
            .let(::Money)

    @Test
    fun aPurchaseBelowTheOnlyVersionsPriceIsStoredProjectedAndFlagged() = runTest {
        linked()
        receiver.applier.applyDocuments(listOf(editor.version(screen, 100)))
        val forged = purchase(lamport = 140, amount = 1)

        assertEquals(EventApplyReport(applied = 1), receiver.ingest(forged))

        val row = receiver.database.ledgerEventDao().all().single { it.eventId == idOf(forged) }
        assertFalse(row.quarantined)
        assertEquals(Money(499), receiver.balance(receiver.gbp), "debited by the forged amount")
        val flag = receiver.flags().single()
        assertEquals(idOf(forged), flag.eventId)
        assertEquals(row.hash, flag.hash)
        assertEquals(PurchaseFlagReason.NO_MATCHING_VERSION, flag.reason)
        assertEquals(Money(1), flag.amount)
        val audit = receiver.database.auditLogDao().all().single { it.action == AuditAction.PURCHASE_FLAGGED }
        assertEquals(idOf(forged).value, audit.target)
        assertEquals(kid.deviceId, audit.deviceId)
        assertTrue("NO_MATCHING_VERSION" in audit.detailsJson)
        assertFalse("amount" in audit.detailsJson || "30 minutes" in audit.detailsJson, "ids and reason only")

        val listed = receiver.purchaseFlags.observePurchaseFlags().first().single()
        assertEquals(KID_X, listed.kidId)
        assertEquals(Money(30), listed.minutesGranted)
        assertEquals(CurrentReward("30 minutes", Money(100), receiver.gbp, 30), listed.currentReward)
    }

    @Test
    fun moreMinutesThanTheRewardGrantsFlagsThePurchaseButNotItsCredit() = runTest {
        linked()
        receiver.applier.applyDocuments(listOf(editor.version(screen, 100)))
        val greedy = purchase(lamport = 140, amount = 100, minutes = 600)
        val credit = kidEvent(minutes, 141) { TimeBankCredited(it, Money(600), idOf(greedy), screen.id.value) }

        assertEquals(EventApplyReport(applied = 2), receiver.ingest(greedy, credit))

        assertEquals(Money(600), receiver.balance(Currency.Minutes))
        assertEquals(Money(400), receiver.balance(receiver.gbp))
        assertEquals(listOf(idOf(greedy)), receiver.flags().map { it.eventId })
    }

    @Test
    fun unknownInactiveAndSiblingOnlyRewardsAreFlaggedAndATombstonesEarlierVersionStillSells() = runTest {
        linked()
        val off = screen.copy(id = RewardId("off"), active = false)
        val sibling = screen.copy(id = RewardId("sibling"), forKids = setOf(KidId("kid-y")))
        val gone = screen.copy(id = RewardId("gone"))
        val blank = screen.copy(id = RewardId("blank"))
        receiver.applier.applyDocuments(
            listOf(
                editor.version(off, 100),
                editor.version(sibling, 101),
                editor.version(gone, 102),
                editor.version(gone, 130, deleted = true),
                // A tombstone whose payload does not decode: kept as a marker only.
                editor.version(blank, 131, deleted = true, payload = JsonObject(emptyMap())),
            ),
        )
        val ghost = purchase(140, 100, rewardId = "ghost")
        val inactive = purchase(141, 100, rewardId = off.id.value)
        val siblings = purchase(142, 100, rewardId = sibling.id.value)
        val deleted = purchase(143, 100, rewardId = gone.id.value)
        val marked = purchase(144, 100, rewardId = blank.id.value)

        assertEquals(EventApplyReport(applied = 5), receiver.ingest(ghost, inactive, siblings, deleted, marked))

        assertEquals(
            mapOf(
                idOf(ghost) to PurchaseFlagReason.NO_SUCH_REWARD,
                idOf(inactive) to PurchaseFlagReason.NO_MATCHING_VERSION,
                idOf(siblings) to PurchaseFlagReason.NO_MATCHING_VERSION,
                idOf(marked) to PurchaseFlagReason.NO_MATCHING_VERSION,
            ),
            receiver.flags().associate { it.eventId to it.reason },
        )
        val markers = receiver.database.rewardVersionDao().all().filter { it.rewardId == blank.id }
        assertNull(markers.single().cost, "the marker has no price")
        // The list shows the reward as it is now: none for an unknown id or a tombstone.
        val current = receiver.purchaseFlags.observePurchaseFlags().first().associate { it.eventId to it.currentReward }
        assertNull(current.getValue(idOf(ghost)))
        assertNull(current.getValue(idOf(marked)))
        assertEquals(Money(100), current.getValue(idOf(inactive))?.cost)
    }

    @Test
    fun aReceiverThatSawTheOldVersionDoesNotFlagAPurchaseRacingAPriceRise() = runTest {
        linked()
        receiver.applier.applyDocuments(listOf(editor.version(screen, 100)))
        receiver.applier.applyDocuments(listOf(editor.version(raised, 120)))
        assertEquals(2, receiver.database.rewardVersionDao().all().size)

        assertEquals(EventApplyReport(applied = 1), receiver.ingest(purchase(lamport = 140, amount = 100)))

        assertTrue(receiver.flags().isEmpty())
    }

    @Test
    fun aReceiverThatMissedTheOldVersionFlagsOnlyPurchasesStampedAfterItsHistoryStarted() = runTest {
        linked()
        receiver.applier.applyDocuments(listOf(editor.version(raised, 120)))
        val racing = purchase(lamport = 140, amount = 100)
        val restored = purchase(lamport = 141, amount = 100)

        receiver.ingest(racing, stamp = after)
        receiver.ingest(restored, stamp = before)

        // The known false positive (D-REL-65 §3): flagged, for a parent to dismiss.
        assertEquals(listOf(idOf(racing)), receiver.flags().map { it.eventId })
        assertEquals(PurchaseFlagReason.NO_MATCHING_VERSION, receiver.flags().single().reason)
    }

    @Test
    fun anOlderVersionPulledLateIsRecordedAndStillSells() = runTest {
        linked()
        receiver.applier.applyDocuments(listOf(editor.version(raised, 120)))
        receiver.applier.applyDocuments(listOf(editor.version(screen, 100)))

        assertEquals(Money(200), receiver.database.rewardDao().get(screen.id)?.cost, "v1 lost last-writer-wins")
        assertEquals(listOf(100L, 120L), receiver.database.rewardVersionDao().all().map { it.lamport })
        receiver.ingest(purchase(lamport = 140, amount = 100))
        assertTrue(receiver.flags().isEmpty())
    }

    @Test
    fun aFlagIsDroppedAndAuditedOnceAVersionThatSellsThePurchaseArrives() = runTest {
        linked()
        val early = purchase(lamport = 140, amount = 100)
        receiver.ingest(early)
        assertEquals(PurchaseFlagReason.NO_SUCH_REWARD, receiver.flags().single().reason)

        // Versions that do not sell it change nothing: another price, the right price above the purchase's lamport,
        // and a version already recorded.
        receiver.applier.applyDocuments(listOf(editor.version(raised, 120)))
        receiver.applier.applyDocuments(listOf(editor.version(screen, 150)))
        receiver.applier.applyDocuments(listOf(editor.version(raised, 120)))
        assertEquals(listOf(idOf(early)), receiver.flags().map { it.eventId })

        receiver.applier.applyDocuments(listOf(editor.version(screen, 100)))
        assertTrue(receiver.flags().isEmpty())
        val audit = receiver.database.auditLogDao().all()
        val cleared = audit.single { it.action == AuditAction.PURCHASE_FLAG_CLEARED }
        assertEquals(audit.single { it.action == AuditAction.PURCHASE_FLAGGED }.target, cleared.target)
        assertEquals(idOf(early).value, cleared.target)
        assertTrue("\"versionLamport\":100" in cleared.detailsJson)
        assertFalse("30 minutes" in cleared.detailsJson)
    }

    @Test
    fun aLocalWriteIsRecordedAndSellsOnlyPurchasesAfterIt() = runTest {
        linked()
        val early = purchase(lamport = 140, amount = 100)
        receiver.ingest(early)
        assertEquals(1, receiver.flags().size)

        receiver.rewards.upsert(screen)

        val local = receiver.database.rewardVersionDao().all().single()
        assertEquals(receiver.deviceId, local.authorDeviceId)
        assertNotNull(local.signature)
        // A local write sorts after everything this device has seen, so it cannot sell the earlier purchase.
        assertTrue(local.lamport > 140)
        assertEquals(listOf(idOf(early)), receiver.flags().map { it.eventId })
        // It sells a later one: written here in a PARENT session.
        receiver.ingest(purchase(lamport = local.lamport + 1, amount = 100))
        assertEquals(listOf(idOf(early)), receiver.flags().map { it.eventId })
    }

    @Test
    fun receiversWithDifferentHistoriesConvergeAndOnlyTheirFlagsDiffer() = runTest {
        val other = parentDevice().also(others::add)
        registerEachOther(receiver, editor, kid, other)
        linked()
        other.applier.applyRegistry(emptyList(), receiver.serverRegisteredKeys())
        other.applier.applyDocuments(receiver.queuedDocuments())
        val funding = receiver.queuedEvents()
        receiver.applier.applyDocuments(listOf(editor.version(screen, 100), editor.version(raised, 120)))
        other.applier.applyDocuments(listOf(editor.version(raised, 120)))
        val bought = purchase(lamport = 140, amount = 100)
        val credit = kidEvent(minutes, 141) { TimeBankCredited(it, Money(30), idOf(bought), screen.id.value) }

        receiver.ingest(bought, credit)
        assertEquals(EventApplyReport(held = 1), other.ingest(credit))
        other.ingest(bought)
        other.ingest(*funding.toTypedArray())

        suspend fun TestHousehold.stored() = database.ledgerEventDao().all()
            .map { Triple(it.eventId, it.hash, it.quarantined) }.toSet()
        assertEquals(receiver.stored(), other.stored())
        assertEquals(3, receiver.stored().size)
        assertEquals(
            receiver.ledger.observeBalances(KID_X).first().map { it.currency to it.amount }.toSet(),
            other.ledger.observeBalances(KID_X).first().map { it.currency to it.amount }.toSet(),
        )
        assertEquals(receiver.ledger.observeAnomalies(KID_X).first(), other.ledger.observeAnomalies(KID_X).first())
        assertTrue(receiver.flags().isEmpty())
        assertEquals(listOf(idOf(bought)), other.flags().map { it.eventId })
    }

    @Test
    fun aVersionRowEditedAfterItWasRecordedNoLongerSells() = runTest {
        linked()
        receiver.applier.applyDocuments(listOf(editor.version(screen, 100)))
        receiver.database.openHelper.writableDatabase.execSQL("UPDATE reward_versions SET cost = 1")

        receiver.ingest(purchase(lamport = 140, amount = 1))

        assertEquals(PurchaseFlagReason.NO_MATCHING_VERSION, receiver.flags().single().reason)
    }

    @Test
    fun aKidSessionNeverFlags() = runTest {
        val sibling = kidDevice().also(others::add)
        registerEachOther(receiver, editor, kid, sibling)
        linked()
        sibling.pullFrom(receiver)
        val v1 = editor.version(screen, 100)
        sibling.applier.applyDocuments(listOf(v1))
        receiver.applier.applyDocuments(listOf(v1))
        val forged = purchase(lamport = 140, amount = 1)

        assertEquals(EventApplyReport(applied = 1), sibling.ingest(forged))
        receiver.ingest(forged)

        assertTrue(sibling.flags().isEmpty())
        assertEquals(1, receiver.flags().size)
    }

    @Test
    fun onlyStampedKidPurchasesAreReviewed() = runTest {
        linked()
        val reviewed = mutableListOf<EventId>()
        val ledger = RoomLedgerRepository(
            receiver.database,
            receiver.clock,
            DeviceKeyEventCheck(receiver.signer, JceEcdsaVerifier()),
            HouseholdPolicySource(receiver.database, JceEcdsaVerifier()),
            RewardTableCatalogue(receiver.database, receiver.signatureCheck),
            receiver.registry,
            receiver.signatureCheck,
            receiver.calendar,
        ) { event, _ -> reviewed += event.eventId }
        // A parent buying on the kid's behalf, signed by the editor device.
        val byParent = editor.craft(KID_X, main.id, role = Role.PARENT) {
            RewardPurchased(it, editor.gbp, Money(1), screen.id.value, Money(30))
        }
        val unstamped = purchase(lamport = 140, amount = 1)
        val stamped = purchase(lamport = 141, amount = 1)

        ledger.ingest(listOf(PulledEvent(byParent, after)))
        ledger.ingest(listOf(PulledEvent(unstamped, null)))
        ledger.ingest(listOf(PulledEvent(stamped, after)))

        val parentRow = receiver.database.ledgerEventDao().all().single { it.eventId == idOf(byParent) }
        assertFalse(parentRow.quarantined, "the parent purchase was stored, and still not reviewed")
        assertEquals(listOf(idOf(stamped)), reviewed)

        // Through the production wiring, the unstamped purchase is not flagged either.
        val other = purchase(lamport = 142, amount = 1)
        receiver.ingest(other, stamp = null)
        assertTrue(receiver.flags().isEmpty())
    }

    @Test
    fun theAuthorStillPaysTheCurrentPriceLocally() = runTest {
        linked()
        kid.pullFrom(receiver)
        kid.applier.applyDocuments(listOf(editor.version(screen, 100), editor.version(raised, 120)))
        assertEquals(2, kid.database.rewardVersionDao().all().size)

        val old = kid.writer.appendAsKid(KID_X, main.id) {
            RewardPurchased(it, kid.gbp, Money(100), screen.id.value, Money(30))
        }
        assertEquals(RoomLedgerRepository.CATALOGUE_REJECTION, assertIs<AppendResult.Rejected>(old).reason)
        val current = kid.writer.appendAsKid(KID_X, main.id) {
            RewardPurchased(it, kid.gbp, Money(200), screen.id.value, Money(30))
        }
        assertEquals(AppendResult.Accepted, current)
    }

    @Test
    fun theDeviceWideStartDecidesEvenForARewardFirstSeenAfterThePurchase() = runTest {
        linked()
        val start = TestHousehold.DEFAULT_START
        // The device's history starts at T0 with another reward.
        receiver.applier.applyDocuments(listOf(editor.version(screen.copy(id = RewardId("other")), 90)))
        // Two hours later one pull brings a new reward R and a forged purchase of R stamped at T0 + 1h.
        receiver.clock.advanceBy(2.hours)
        receiver.applier.applyDocuments(listOf(editor.version(screen, 100)))
        val forged = purchase(lamport = 140, amount = 1)
        receiver.ingest(forged, stamp = start + 1.hours)
        // An unknown reward id falls back to the same start.
        val ghostBefore = purchase(lamport = 141, amount = 1, rewardId = "ghost")
        val ghostAfter = purchase(lamport = 142, amount = 1, rewardId = "ghost")
        receiver.ingest(ghostBefore, stamp = start - 1.hours)
        receiver.ingest(ghostAfter, stamp = start + 1.hours)

        assertEquals(
            mapOf(
                idOf(forged) to PurchaseFlagReason.NO_MATCHING_VERSION,
                idOf(ghostAfter) to PurchaseFlagReason.NO_SUCH_REWARD,
            ),
            receiver.flags().associate { it.eventId to it.reason },
        )
    }

    @Test
    fun aFlaggedPurchaseLaterQuarantinedOnAForkLeavesTheList() = runTest {
        linked()
        receiver.applier.applyDocuments(listOf(editor.version(screen, 100)))
        val forged = purchase(lamport = 140, amount = 1)
        receiver.ingest(forged)
        assertEquals(1, receiver.purchaseFlags.observePurchaseFlags().first().size)

        // A second event of the kid device on the same predecessor: both branches are quarantined (D-M3-31).
        val branch = kid.craft(KID_X, main.id, lamport = 141) {
            RewardPurchased(it, kid.gbp, Money(100), screen.id.value, Money(30))
        }
        receiver.ingest(branch)

        val row = receiver.database.ledgerEventDao().all().single { it.eventId == idOf(forged) }
        assertTrue(row.quarantined)
        assertTrue(receiver.purchaseFlags.observePurchaseFlags().first().isEmpty())
    }

    @Test
    fun aConflictingCopyOfAKidPurchaseIsReviewedToo() = runTest {
        linked()
        receiver.applier.applyDocuments(listOf(editor.version(screen, 100)))
        val genuine = purchase(lamport = 140, amount = 100)
        receiver.ingest(genuine)
        assertTrue(receiver.flags().isEmpty())
        val balance = receiver.balance(receiver.gbp)

        // A different event under the same eventId, chained after the genuine one: kept as a conflict copy.
        val copy = purchase(lamport = 141, amount = 1, eventId = idOf(genuine))
        assertEquals(EventApplyReport(applied = 1), receiver.ingest(copy))

        val copyHash = assertNotNull(LedgerJson.decodeOrNull(copy)).envelope.hash
        val flag = receiver.flags().single()
        assertEquals(idOf(genuine) to copyHash, flag.eventId to flag.hash)
        assertTrue(
            receiver.database.auditLogDao().all().any {
                it.action == AuditAction.LEDGER_EVENT_CONFLICT && it.target == idOf(genuine).value
            },
        )
        // The projection decides between the copies as before; the flag changes nothing there.
        assertEquals(balance, receiver.balance(receiver.gbp))
    }

    @Test
    fun aHeldPurchaseIsReviewedWithItsKeptStampWhenReleased() = runTest {
        linked()
        receiver.applier.applyDocuments(listOf(editor.version(screen, 100)))
        val first = purchase(lamport = 140, amount = 100)
        val forged = purchase(lamport = 141, amount = 1)

        assertEquals(EventApplyReport(held = 1), receiver.ingest(forged, stamp = after))
        assertTrue(receiver.flags().isEmpty())
        // The gap is filled by a pull without stamps: the held purchase keeps the one it came with.
        assertEquals(EventApplyReport(applied = 2), receiver.ingest(first, stamp = null))

        assertEquals(listOf(idOf(forged)), receiver.flags().map { it.eventId })
    }

    @Test
    fun aCorruptVersionRowFailsClosedWithoutAbortingTheIngest() = runTest {
        linked()
        receiver.applier.applyDocuments(listOf(editor.version(screen, 100)))
        // A negative lamport cannot rebuild into an envelope (DocumentEnvelope refuses it).
        receiver.database.openHelper.writableDatabase.execSQL("UPDATE reward_versions SET lamport = -1")
        val bought = purchase(lamport = 140, amount = 100)

        assertEquals(EventApplyReport(applied = 1), receiver.ingest(bought))

        assertEquals(PurchaseFlagReason.NO_MATCHING_VERSION, receiver.flags().single().reason)
        // And a document pulled afterwards still applies: the heal runs the same check.
        receiver.applier.applyDocuments(listOf(editor.version(raised, 120)))
        assertEquals(Money(200), receiver.database.rewardDao().get(screen.id)?.cost)
    }

    @Test
    fun aFlagAndItsRewardRedactTheTitle() = runTest {
        linked()
        receiver.applier.applyDocuments(listOf(editor.version(screen, 100)))
        receiver.ingest(purchase(lamport = 140, amount = 1))

        val listed = receiver.purchaseFlags.observePurchaseFlags().first().single()
        assertEquals("30 minutes", listed.currentReward?.title)
        assertFalse("30 minutes" in listed.toString())
        assertTrue("<redacted>" in listed.toString())
    }

    @Test
    fun aDismissedFlagIsNoLongerListed() = runTest {
        linked()
        receiver.applier.applyDocuments(listOf(editor.version(screen, 100)))
        val forged = purchase(lamport = 140, amount = 1)
        receiver.ingest(forged)
        val listed = receiver.purchaseFlags.observePurchaseFlags().first().single()

        receiver.clock.advanceBy(1.hours)
        receiver.purchaseFlags.dismissPurchaseFlag(listed.eventId, listed.hash)

        assertTrue(receiver.purchaseFlags.observePurchaseFlags().first().isEmpty())
        assertEquals(receiver.clock.now().toEpochMilliseconds(), receiver.flags().single().dismissedAtEpochMs)
        // Dismissing changes nothing in the ledger.
        assertEquals(Money(499), receiver.balance(receiver.gbp))
    }
}
