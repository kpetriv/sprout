package dev.kpetriv.sprout.core.database

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import java.util.UUID
import javax.inject.Inject

/** The repositories [HouseholdBootstrap] seeds through, grouped so the bootstrap's own constructor stays short. */
@Suppress("LongParameterList") // One per seeded collection.
class HouseholdSeedTargets @Inject constructor(
    val kids: KidRepository,
    val accounts: AccountRepository,
    val chores: ChoreRepository,
    val rewards: RewardRepository,
    val allowances: AllowanceRepository,
    val goals: SavingsGoalRepository,
    val policies: HouseholdPolicyRepository,
)

/**
 * TEMPORARY (debug builds only, replaced by onboarding's household setup in M3): seeds the local trial household so
 * the wallet, chores and shop have data before sign-in exists. Runs only while there is no session or the session is
 * the trial session ([SessionStore.ensureLocalTrialSession]); a device signed in to a real household is never seeded.
 */
@Suppress("LongParameterList") // Storage, seed targets, session, signer, currency, clock and calendar.
class HouseholdBootstrap @Inject constructor(
    private val database: SproutDatabase,
    private val targets: HouseholdSeedTargets,
    private val sessions: SessionStore,
    private val signer: DeviceSigner,
    private val currency: LocaleFiatCurrencyProvider,
    private val clock: Clock,
    private val calendar: HouseholdCalendar,
) {

    /**
     * Makes sure the trial session exists (creating it for this device's key when there is no session; a real
     * session means no seeding and false), then, if no kid exists (archived ones included), creates the demo
     * household and returns true:
     * - three kids, one per [KidUiTier] (Mia 6, Leo 9, Zoe 13), each with its default accounts in the household's
     *   fiat currency, and the household policy with that currency;
     * - two chores per kid (one paying money, one money and screen time);
     * - three rewards (screen time, a treat, an outing);
     * - a weekly allowance for Leo and a savings goal for Zoe.
     *
     * The device id (the Keystore key's fingerprint) is read before the transaction opens, so no Keystore read runs
     * while the database is locked except the document signatures.
     */
    suspend fun ensureLocalHousehold(): Boolean {
        val session = sessions.get()
        if (session != null && !session.isLocalTrial) return false
        if (session == null) sessions.ensureLocalTrialSession(signer.deviceId())
        return seed()
    }

    private suspend fun seed(): Boolean = database.withTransaction {
        if (database.kidDao().count() > 0) return@withTransaction false
        val today = calendar.today()
        // The trial household's currency is the locale's, read directly: the household provider would answer with a
        // previous household's policy until it reads storage again (review Low, D-REL-20).
        val fiat = currency.fiatCurrency()
        targets.policies.ensure(fiat)
        val seeded = DEMO_KIDS.mapIndexed { index, (name, age) ->
            val kid = Kid(
                id = KidId(UUID.randomUUID().toString()),
                householdId = LocalTrial.HOUSEHOLD,
                firstName = name,
                avatarId = "avatar-${index + 1}",
                birthYearMonth = YearMonth(today.year - age, today.month),
                uiTier = KidUiTier.suggestedFor(age),
            )
            targets.kids.upsert(kid)
            kid to targets.accounts.ensureDefaultAccounts(kid.id, fiat)
        }
        seeded.forEach { (kid, _) -> seedChores(kid, today) }
        seedRewards(fiat)
        val (leo, leoAccounts) = seeded[LEO]
        val leoMain = leoAccounts.first { it.kind == AccountKind.MAIN && it.currency == fiat }
        targets.allowances.upsert(weeklyAllowance(leo, leoMain, fiat, today))
        val zoe = seeded[ZOE].first
        targets.goals.upsert(
            SavingsGoal(GoalId(UUID.randomUUID().toString()), zoe.id, "New bike", Money(BIKE_TARGET), today),
        )
        true
    }

    private suspend fun seedChores(kid: Kid, today: CalendarDate) {
        val now = clock.now()
        listOf(
            Triple("Make your bed", ChoreReward(money = Money(MAKE_BED_PAY)), ChoreSchedule.Daily.EVERY_DAY),
            Triple(
                "Tidy your room",
                ChoreReward(money = Money(TIDY_PAY), minutes = Money(TIDY_MINUTES)),
                ChoreSchedule.Weekly(today.dayOfWeek),
            ),
        ).forEach { (title, reward, schedule) ->
            targets.chores.upsert(
                Chore(
                    id = ChoreId(UUID.randomUUID().toString()),
                    householdId = kid.householdId,
                    kidId = kid.id,
                    title = title,
                    reward = reward,
                    schedule = schedule,
                    createdAt = now,
                ),
            )
        }
    }

    private suspend fun seedRewards(fiat: Currency.Fiat) {
        listOf(
            Triple("30 minutes of screen time", RewardKind.SCREEN_TIME, SCREEN_TIME_COST),
            Triple("Ice cream", RewardKind.TREAT, ICE_CREAM_COST),
            Triple("Trip to the zoo", RewardKind.OUTING, ZOO_COST),
        ).forEach { (title, kind, cost) ->
            targets.rewards.upsert(
                Reward(
                    id = RewardId(UUID.randomUUID().toString()),
                    householdId = LocalTrial.HOUSEHOLD,
                    title = title,
                    kind = kind,
                    cost = Money(cost),
                    currency = fiat,
                    minutesGranted = SCREEN_TIME_MINUTES.takeIf { kind == RewardKind.SCREEN_TIME },
                ),
            )
        }
    }

    private fun weeklyAllowance(kid: Kid, main: Account, fiat: Currency.Fiat, today: CalendarDate) = AllowanceSchedule(
        id = ScheduleId(UUID.randomUUID().toString()),
        kidId = kid.id,
        accountId = main.id,
        amount = Money(WEEKLY_ALLOWANCE),
        currency = fiat,
        cadence = AllowanceCadence.Weekly(DayOfWeek.SATURDAY),
        startsOn = today,
    )

    private companion object {
        /** First name and age in whole years: one kid per tier (Seedling, Sprout, Sapling). */
        val DEMO_KIDS = listOf("Mia" to 6, "Leo" to 9, "Zoe" to 13)
        const val LEO = 1
        const val ZOE = 2

        // Minor units of the household currency, or minutes.
        const val MAKE_BED_PAY = 20L
        const val TIDY_PAY = 50L
        const val TIDY_MINUTES = 15L
        const val SCREEN_TIME_COST = 100L
        const val SCREEN_TIME_MINUTES = 30
        const val ICE_CREAM_COST = 150L
        const val ZOO_COST = 1_000L
        const val WEEKLY_ALLOWANCE = 300L
        const val BIKE_TARGET = 5_000L
    }
}
