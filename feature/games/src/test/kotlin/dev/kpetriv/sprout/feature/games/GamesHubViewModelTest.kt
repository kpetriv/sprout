package dev.kpetriv.sprout.feature.games

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GamesHubViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val cap = FakeCapSource()

    private fun viewModel(tier: KidUiTier) = GamesHubViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(GamesHubViewModel.KID_ID_KEY to TestData.kid.value, GamesHubViewModel.TIER_KEY to tier.name),
        ),
        cap = cap,
        currency = { TestData.gbp },
    )

    @Test
    fun `each tier sees its games and what it can still earn`() = runTest {
        viewModel(KidUiTier.SEEDLING).state.test {
            val ready = assertIs<GamesHubUiState.Ready>(expectMostRecentItem())
            assertEquals(listOf(GameKind.ARITHMETIC, GameKind.MEMORY), ready.games)
            assertEquals(Money(23), ready.remaining)
            assertEquals(TestData.gbp, ready.currency)
            assertFalse(ready.capReached)
        }
        viewModel(KidUiTier.SAPLING).state.test {
            val ready = assertIs<GamesHubUiState.Ready>(expectMostRecentItem())
            assertEquals(GameKind.entries, ready.games)
        }
    }

    @Test
    fun `the cap updates live and reaching it is shown`() = runTest {
        viewModel(KidUiTier.SPROUT).state.test {
            assertFalse(assertIs<GamesHubUiState.Ready>(expectMostRecentItem()).capReached)
            cap.remaining.value = Money(0)
            assertTrue(assertIs<GamesHubUiState.Ready>(awaitItem()).capReached)
        }
    }

    @Test
    fun `a failure shows the error, and retry loads again`() = runTest {
        cap.fail = true
        val vm = viewModel(KidUiTier.SPROUT)
        vm.state.test {
            assertEquals(GamesHubUiState.Error, expectMostRecentItem())
            cap.fail = false
            vm.onEvent(GamesHubEvent.Retry)
            assertIs<GamesHubUiState.Ready>(expectMostRecentItem())
        }
    }
}
