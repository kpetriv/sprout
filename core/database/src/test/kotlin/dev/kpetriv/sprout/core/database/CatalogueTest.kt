package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.database.usecase.PurchaseResult
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.ManualAdjustment
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The reward catalogue a kid device buys from must be parent-authored (D-M3-3, T27). */
@RunWith(RobolectricTestRunner::class)
class CatalogueTest {
    private val parent = parentDevice()
    private val kid = kidDevice()

    @After
    fun close() {
        parent.close()
        kid.close()
    }

    private fun treat(id: String, cost: Long = 100) =
        Reward(RewardId(id), HOME, "Treat $id", RewardKind.TREAT, Money(cost), parent.gbp)

    /** The kid exists on both devices with 500 in its money account, and the parent's catalogue holds "treat". */
    private suspend fun funded() {
        registerEachOther(parent, kid)
        val (_, accounts) = parent.addKid(KID_X.value)
        parent.registerParentKey()
        val main = with(parent) { accounts.money() }
        val funding = parent.writer.appendAsParent(KID_X, main.id, parent.authorization()) {
            ManualAdjustment(it, parent.gbp, Money(500), "start")
        }
        assertEquals(AppendResult.Accepted, funding)
        parent.rewards.upsert(treat("treat"))
        kid.pullFrom(parent)
    }

    private fun assertRefusedByTheCatalogue(result: PurchaseResult) {
        val refused = assertIs<PurchaseResult.Refused>(result)
        assertEquals(RoomLedgerRepository.CATALOGUE_REJECTION, assertIs<AppendResult.Rejected>(refused.result).reason)
    }

    @Test
    fun aRewardSignedByAParentDeviceCanBeBought() = runTest {
        funded()
        assertIs<PurchaseResult.Purchased>(kid.purchase(KID_X, RewardId("treat")))
    }

    @Test
    fun aRewardTheKidDeviceWroteItselfIsRefused() = runTest {
        funded()
        kid.rewards.upsert(treat("cheap", cost = 1))
        assertRefusedByTheCatalogue(kid.purchase(KID_X, RewardId("cheap")))
        // Rewriting the parent's reward locally takes it over too.
        kid.rewards.upsert(treat("treat", cost = 1))
        assertRefusedByTheCatalogue(kid.purchase(KID_X, RewardId("treat")))
    }

    @Test
    fun anUnsignedOrAlteredRemoteRewardIsRefused() = runTest {
        funded()
        val rewards = kid.database.rewardDao()
        val signed = rewards.get(RewardId("treat"))!!
        rewards.upsert(signed.copy(stamp = signed.stamp.copy(signature = null)))
        assertRefusedByTheCatalogue(kid.purchase(KID_X, RewardId("treat")))

        // The parent device's signature no longer covers a cheaper price.
        rewards.upsert(signed.copy(cost = Money(1)))
        assertRefusedByTheCatalogue(kid.purchase(KID_X, RewardId("treat")))

        rewards.upsert(signed)
        assertIs<PurchaseResult.Purchased>(kid.purchase(KID_X, RewardId("treat")))
    }
}
