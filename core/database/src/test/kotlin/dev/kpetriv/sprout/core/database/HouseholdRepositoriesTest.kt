package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.InterestPolicy
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.sync.DocTypes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class HouseholdRepositoriesTest {
    private val household = testHousehold()
    private val database = household.database
    private val kid = KidId("kid-leo")
    private val other = KidId("kid-zoe")
    private val today = CalendarDate(2026, 11, 2)

    @After
    fun close() = household.close()

    private suspend fun documents(docType: String) =
        database.outboxDao().all().filter { it.kind == OutboxKind.DOCUMENT && it.docType == docType }

    @Test
    fun rewardsFilterByKidAndActivity() = runTest {
        val rewards = household.rewards
        val screen = Reward(
            RewardId("screen"),
            LocalTrial.HOUSEHOLD,
            "Screen time",
            RewardKind.SCREEN_TIME,
            Money(100),
            household.gbp,
            minutesGranted = 30,
        )
        val zoeOnly = Reward(
            RewardId("zoo"),
            LocalTrial.HOUSEHOLD,
            "Zoo",
            RewardKind.OUTING,
            Money(1000),
            household.gbp,
            forKids = setOf(other),
        )
        rewards.upsert(screen)
        rewards.upsert(zoeOnly)

        assertEquals(screen, rewards.get(screen.id))
        assertEquals(listOf(screen, zoeOnly), rewards.observeRewards().first())
        assertEquals(listOf(screen), rewards.observeRewards(kid).first())
        assertEquals(listOf(screen, zoeOnly), rewards.observeRewards(other).first())

        assertTrue(rewards.setActive(screen.id, active = false))
        assertFalse(rewards.setActive(RewardId("missing"), active = false))
        assertEquals(listOf(zoeOnly), rewards.observeRewards().first())
        assertEquals(2, rewards.observeRewards(includeInactive = true).first().size)
        assertEquals(emptyList(), rewards.observeRewards(kid).first())
        assertEquals(setOf("screen", "zoo"), documents(DocTypes.REWARD).map { it.docId }.toSet())
        assertTrue(database.auditLogDao().all().none { "Screen time" in it.detailsJson })
    }

    @Test
    fun allowanceKeepsItsPlanningBookkeepingAcrossEdits() = runTest {
        val allowances = household.allowances
        val (leo, accounts) = household.addKid()
        val weekly = AllowanceSchedule(
            ScheduleId("leo-weekly"),
            leo.id,
            with(household) { accounts.money() }.id,
            Money(300),
            household.gbp,
            AllowanceCadence.Weekly(DayOfWeek.SATURDAY),
            startsOn = today,
        )
        allowances.upsert(weekly)
        assertEquals(weekly, allowances.get(weekly.id))
        assertNull(allowances.lastPaidOn(weekly.id))

        allowances.recordPaidThrough(weekly.id, today.plusDays(5))
        allowances.recordPaidThrough(weekly.id, today) // never moves backwards
        val monthly = weekly.copy(cadence = AllowanceCadence.Monthly(15), amount = Money(1200))
        allowances.upsert(monthly)

        assertEquals(listOf(monthly), allowances.observeSchedules(leo.id).first())
        assertEquals(listOf(monthly), allowances.observeSchedules().first())
        assertEquals(today.plusDays(5), allowances.lastPaidOn(weekly.id))
        assertEquals(listOf("leo-weekly"), documents(DocTypes.ALLOWANCE_SCHEDULE).map { it.docId })
        assertTrue("1200" in documents(DocTypes.ALLOWANCE_SCHEDULE).single().payload)
    }

    @Test
    fun savingsGoalsCanBeDeletedWithATombstone() = runTest {
        val goals = household.goals
        val bike = SavingsGoal(GoalId("bike"), kid, "New bike", Money(5000), today)
        goals.upsert(bike)
        assertEquals(listOf(bike), goals.observeGoals(kid).first())
        val reached = bike.copy(achievedOn = today.plusDays(30))
        goals.upsert(reached)
        assertEquals(reached, goals.get(bike.id))

        assertTrue(goals.delete(bike.id))
        assertFalse(goals.delete(bike.id))
        assertEquals(emptyList(), goals.observeGoals(kid).first())
        val tombstone = documents(DocTypes.SAVINGS_GOAL).single()
        assertTrue("\"deleted\":true" in tombstone.payload)
        assertEquals(1, database.auditLogDao().all().count { it.action == AuditAction.SAVINGS_GOAL_DELETED })
    }

    @Test
    fun interestPolicyDefaultsToOffAndKeepsPerAccountBookkeeping() = runTest {
        val interest = household.interest
        val home = LocalTrial.HOUSEHOLD
        assertEquals(InterestPolicy(home), interest.get(home))
        assertFalse(interest.observe(home).first().isEnabled)

        interest.set(InterestPolicy(home, annualRateBasisPoints = 500, accrualDay = 1))
        assertEquals(500, interest.observe(home).first().annualRateBasisPoints)
        assertEquals(listOf(home.value), documents(DocTypes.INTEREST_POLICY).map { it.docId })

        val savings = with(household) { household.addKid().second.savings() }
        assertNull(interest.lastAccruedOn(savings.id))
        interest.recordAccruedThrough(savings.id, today)
        interest.recordAccruedThrough(savings.id, today.plusDays(-3))
        assertEquals(today, interest.lastAccruedOn(savings.id))
    }

    @Test
    fun householdPolicyRegistersTheParentKeyFromTheNextLamport() = runTest {
        val policies = household.policies
        assertNull(policies.get())
        val created = policies.ensure(household.gbp)
        assertFalse(created.requireParentSignature)
        assertTrue(created.parentSignaturesOff)
        assertEquals(created, policies.ensure(household.gbp))

        // The policy document and two goals use lamports 1 to 3, so the next write gets 4.
        household.goals.upsert(SavingsGoal(GoalId("a"), kid, "A", Money(1), today))
        household.goals.upsert(SavingsGoal(GoalId("b"), kid, "B", Money(1), today))
        val key = byteArrayOf(1, 2, 3)
        val registered = policies.registerParentKey(key)
        assertTrue(registered.requireParentSignature)
        assertEquals(4L, registered.parentSignatureFromLamport)
        assertContentEquals(key, registered.parentPublicKey)
        assertEquals(household.gbp, registered.fiatCurrency)
        assertEquals(registered.toLedgerPolicy().parentSignatureRequiredFromLamport, 4L)

        // A replacement key keeps the original cut-off.
        household.goals.upsert(SavingsGoal(GoalId("c"), kid, "C", Money(1), today))
        val replaced = policies.registerParentKey(byteArrayOf(4, 5))
        assertEquals(4L, replaced.parentSignatureFromLamport)
        assertEquals(replaced, policies.observe().first())
        val audit = database.auditLogDao().all().filter { it.action == AuditAction.PARENT_KEY_REGISTERED }
        assertEquals(2, audit.size)
        // M3: the policy is a synced document and each key a parent_key document named by its fingerprint.
        assertEquals(1, documents(DocTypes.HOUSEHOLD_POLICY).size)
        assertEquals(2, documents(DocTypes.PARENT_KEY).size)
        assertEquals(2, database.parentKeyDao().all().size)
    }

    @Test
    fun pendingActionsAreQueuedOncePerKeyAndMarkedDone() = runTest {
        val pending = household.pending
        val (leo, accounts) = household.addKid()
        val pay = PendingParentAction.PayAllowance(
            ScheduleId("leo-weekly"),
            leo.id,
            with(household) { accounts.money() }.id,
            Money(300),
            household.gbp,
            periodKey = "2026-W45",
            dueOn = today,
        )
        val accrue = PendingParentAction.AccrueInterest(
            leo.id,
            with(household) { accounts.savings() }.id,
            household.gbp,
            principal = Money(10_000),
            annualRateBasisPoints = 500,
            days = 30,
            periodKey = "2026-11",
            amount = Money(41),
        )
        assertTrue(pending.enqueue(pay))
        assertFalse(pending.enqueue(pay))
        assertTrue(pending.enqueue(accrue))
        assertEquals(listOf(pay, accrue), pending.observePending().first())

        val eventId = EventId("00000000-0000-4000-8000-000000000001")
        assertTrue(pending.markDone(pay.key, eventId))
        assertFalse(pending.markDone(pay.key, eventId))
        assertEquals(listOf(accrue), pending.pending())
        // A done key is never queued again.
        assertFalse(pending.enqueue(pay))
        assertEquals(eventId, assertNotNull(database.pendingParentActionDao().get(pay.key)).eventId)
        val audit = database.auditLogDao().all()
        assertEquals(2, audit.count { it.action == AuditAction.PARENT_ACTION_QUEUED })
        assertEquals(1, audit.count { it.action == AuditAction.PARENT_ACTION_DONE })
    }
}
