package dev.kpetriv.sprout.feature.wallet

import app.cash.turbine.test
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.feature.wallet.RewardsTestData.leo
import dev.kpetriv.sprout.feature.wallet.RewardsTestData.mia
import dev.kpetriv.sprout.feature.wallet.RewardsTestData.reward
import dev.kpetriv.sprout.feature.wallet.RewardsTestData.zoe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class RewardListViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val rewards = FakeRewardRepository()
    private val kids = FakeKidRepository(listOf(mia, leo, zoe))

    private fun viewModel() = RewardListViewModel(rewards, kids)

    private suspend fun RewardListViewModel.ready(): RewardListUiState.Ready =
        state.first { it is RewardListUiState.Ready } as RewardListUiState.Ready

    @Test
    fun `loading until the catalogue and the kids are read`() = runTest {
        kids.pending = true

        viewModel().state.test {
            assertEquals(RewardListUiState.Loading, awaitItem())
            expectNoEvents()
        }
    }

    @Test
    fun `no rewards is an empty list`() = runTest {
        assertEquals(RewardListUiState.Ready(emptyList()), viewModel().ready())
    }

    @Test
    fun `every reward is listed, shown ones first and hidden ones last, each cheapest first`() = runTest {
        rewards.rewards.value = listOf(
            reward("hidden-cheap", title = "Sticker pack", cost = 50, active = false),
            reward("zoo", "Trip to the zoo", RewardKind.OUTING, cost = 1_500, forKids = setOf(zoe.id, mia.id)),
            reward("tablet", title = "Tablet time", kind = RewardKind.SCREEN_TIME, cost = 150, minutes = 30),
            reward("ice-cream", cost = 250, forKids = setOf(leo.id)),
            reward("gone", title = "Cinema", kind = RewardKind.OUTING, cost = 900, forKids = setOf(KidId("kid-gone"))),
        )

        val rows = viewModel().ready().rewards

        assertEquals(listOf("tablet", "ice-cream", "gone", "zoo", "hidden-cheap"), rows.map { it.id.value })
        val tablet = rows[0]
        assertEquals(RewardKind.SCREEN_TIME, tablet.kind)
        assertEquals(Money(150), tablet.cost)
        assertEquals(TestData.gbp, tablet.currency)
        assertEquals(30, tablet.minutes)
        assertEquals(RewardAudience.AllKids, tablet.forKids)
        assertEquals(RewardAudience.Kids(listOf("Leo")), rows[1].forKids)
        assertEquals(RewardAudience.Kids(emptyList()), rows[2].forKids, "a kid who left is not named")
        assertEquals(RewardAudience.Kids(listOf("Mia", "Zoe")), rows[3].forKids, "named in the household's order")
        assertEquals(listOf(true, true, true, true, false), rows.map { it.active })
        assertEquals(null, rows[1].minutes)
    }

    @Test
    fun `the list follows the catalogue`() = runTest {
        val vm = viewModel()
        vm.state.test {
            assertEquals(RewardListUiState.Ready(emptyList()), awaitItem())
            rewards.upsert(reward("ice-cream"))
            assertEquals(listOf(RewardId("ice-cream")), (awaitItem() as RewardListUiState.Ready).rewards.map { it.id })
            rewards.setActive(RewardId("ice-cream"), false)
            assertEquals(false, (awaitItem() as RewardListUiState.Ready).rewards.single().active)
        }
    }

    @Test
    fun `a read failure is an error, and retry reads again`() = runTest {
        rewards.failing = true
        val vm = viewModel()
        vm.state.test {
            assertEquals(RewardListUiState.Error, awaitItem())
            rewards.failing = false
            vm.onEvent(RewardListEvent.Retry)
            assertEquals(RewardListUiState.Ready(emptyList()), awaitItem())
        }
    }
}
