package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.FakeParentAuthorizer
import dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore
import dev.kpetriv.sprout.core.crypto.JceEcdsaVerifier
import dev.kpetriv.sprout.core.crypto.ParentSigner
import dev.kpetriv.sprout.core.database.usecase.ApproveSubmissionUseCase
import dev.kpetriv.sprout.core.database.usecase.DrainPendingParentActionsUseCase
import dev.kpetriv.sprout.core.database.usecase.DrainResult
import dev.kpetriv.sprout.core.database.usecase.PlanScheduledActionsUseCase
import dev.kpetriv.sprout.core.database.usecase.PurchaseRewardUseCase
import dev.kpetriv.sprout.core.database.usecase.RecordManualAdjustmentUseCase
import dev.kpetriv.sprout.core.database.usecase.RedeemOwedGrantsUseCase
import dev.kpetriv.sprout.core.database.usecase.RegisterParentKeyUseCase
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.Dispatchers
import java.time.ZoneOffset

/**
 * The data layer wired as production wires it (device-key event check, `household_policy`, reward catalogue), on an
 * in-memory database, with in-memory crypto: a software device key and [FakeParentAuthorizer] for the parent key.
 */
internal class TestHousehold(val clock: FakeClock = testClock()) : AutoCloseable {
    val database = inMemoryDatabase()
    val gbp = Currency.Fiat("GBP")
    private val keyStore = InMemoryDeviceKeyStore(clock)
    val signer = DeviceSigner(keyStore, Dispatchers.Unconfined)
    val authors = LocalOnlyAuthorProvider(signer)
    val ledger = RoomLedgerRepository(database, clock, signer, JceEcdsaVerifier())
    val writer = RoomLedgerWriter(database, ledger, signer, authors, clock)

    val photoDirectory = newPhotoDirectory()
    val photos = testPhotoStore(database, authors, clock, photoDirectory)

    val kids = RoomKidRepository(database, authors, clock, photos)
    val accounts = RoomAccountRepository(database, authors, clock)
    val chores = RoomChoreRepository(database, authors, clock, ledger, photos)
    val rewards = RoomRewardRepository(database, authors, clock)
    val allowances = RoomAllowanceRepository(database, authors, clock)
    val goals = RoomSavingsGoalRepository(database, authors, clock)
    val interest = RoomInterestPolicyRepository(database, authors, clock)
    val policies = RoomHouseholdPolicyRepository(database, authors, { gbp }, clock)
    val pending = RoomPendingParentActionRepository(database, authors, clock)

    val parentAuthorizer = FakeParentAuthorizer()
    private val parentAuthorizations = ParentAuthorizations(ParentSigner())

    val registerKey = RegisterParentKeyUseCase(policies)
    val approve = ApproveSubmissionUseCase(chores, accounts, ledger, writer, policies, clock)
    val purchase = PurchaseRewardUseCase(rewards, accounts, writer)
    val redeem = RedeemOwedGrantsUseCase(ledger, accounts, writer)
    val adjust = RecordManualAdjustmentUseCase(accounts, writer, policies)
    val describer = EnglishPendingActionDescriber(kids)
    val drainUseCase = DrainPendingParentActionsUseCase(pending, ledger, writer, policies, allowances, kids, describer)
    val plan = PlanScheduledActionsUseCase(
        kids,
        accounts,
        allowances,
        interest,
        pending,
        ledger,
        redeem,
        HouseholdCalendar(clock) { ZoneOffset.UTC },
    )

    /** Drains the queue; fails the test if another drain was running. */
    suspend fun drain(provider: suspend (PendingParentAction) -> ParentAuthorization): DrainResult.Drained =
        drainUseCase(provider) as DrainResult.Drained

    /**
     * What `feature/lock`'s PIN-gated enrolment does once the PIN is verified: creates the parent key and registers
     * it. Returns the policy with parent signatures required.
     */
    suspend fun registerParentKey(): HouseholdPolicy = registerKey.register(parentAuthorizer.createParentKey().encoded)

    /** One parent prompt: a fresh single-use authorisation from the parent key (create it first). */
    fun authorization(): ParentAuthorization = parentAuthorizations.from(parentAuthorizer.initSignature())

    /** A kid with default GBP accounts. */
    suspend fun addKid(id: String = "kid-leo", age: Int = 9): Pair<Kid, List<Account>> {
        val kid = Kid(
            KidId(id),
            LocalOnlyAuthorProvider.LOCAL_HOUSEHOLD,
            "Leo",
            "avatar-1",
            YearMonth(2026 - age, 11),
            KidUiTier.suggestedFor(age),
        )
        kids.upsert(kid)
        return kid to accounts.ensureDefaultAccounts(kid.id, gbp)
    }

    fun List<Account>.money(): Account = first { it.kind == AccountKind.MAIN && it.currency == gbp }

    fun List<Account>.savings(): Account = first { it.kind == AccountKind.SAVINGS }

    fun List<Account>.minutes(): Account = first { it.currency == Currency.Minutes }

    override fun close() {
        database.close()
        photoDirectory.deleteRecursively()
    }
}
