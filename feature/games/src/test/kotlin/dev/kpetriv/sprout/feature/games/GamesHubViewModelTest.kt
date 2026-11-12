package dev.kpetriv.sprout.feature.games

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.games.Difficulty
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.games.TablesLevel
import dev.kpetriv.sprout.core.games.TablesSetup
import dev.kpetriv.sprout.core.model.KidUiTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val setups = FakeTablesSetupStore()

    private fun viewModel(tier: KidUiTier, currency: GameCurrencySource = GameCurrencySource { TestData.gbp }) =
        GamesHubViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(GamesHubViewModel.KID_ID_KEY to TestData.kid.value, GamesHubViewModel.TIER_KEY to tier.name),
            ),
            cap = cap,
            currency = currency,
            setups = setups,
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
    fun `the times tables tile shows the stored setup, the default until there is one`() = runTest {
        viewModel(KidUiTier.SEEDLING).state.test {
            assertEquals(null, assertIs<GamesHubUiState.Ready>(expectMostRecentItem()).tablesSetup)
        }
        viewModel(KidUiTier.SPROUT).state.test {
            val default = TablesSetup.defaultFor(Difficulty.SPROUT)
            assertEquals(default, assertIs<GamesHubUiState.Ready>(expectMostRecentItem()).tablesSetup)
            val chosen = TablesSetup(setOf(3, 4, 6), TablesLevel.STEADY)
            setups.save(TestData.kid, chosen)
            assertEquals(chosen, assertIs<GamesHubUiState.Ready>(awaitItem()).tablesSetup)
        }
    }

    @Test
    fun `an unreadable setup store shows the default setup, not an error`() = runTest {
        setups.fail = true
        viewModel(KidUiTier.SAPLING).state.test {
            val ready = assertIs<GamesHubUiState.Ready>(expectMostRecentItem())
            assertEquals(TablesSetup.defaultFor(Difficulty.SAPLING), ready.tablesSetup)
        }
    }

    @Test
    fun `amounts follow the household policy's currency when a pull brings it`() = runTest {
        // A EUR-locale phone of a GBP household before its first pull (D-REL-10).
        val household = MutableStateFlow(Currency.Fiat("EUR"))
        val currency = object : GameCurrencySource {
            override fun fiatCurrency(): Currency.Fiat = household.value

            override fun observeFiatCurrency(): Flow<Currency.Fiat> = household
        }
        viewModel(KidUiTier.SPROUT, currency).state.test {
            assertEquals(Currency.Fiat("EUR"), assertIs<GamesHubUiState.Ready>(expectMostRecentItem()).currency)
            household.value = TestData.gbp
            assertEquals(TestData.gbp, assertIs<GamesHubUiState.Ready>(awaitItem()).currency)
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
    fun `a game credited elsewhere lowers what is left`() = runTest {
        viewModel(KidUiTier.SAPLING).state.test {
            assertEquals(Money(23), assertIs<GamesHubUiState.Ready>(expectMostRecentItem()).remaining)
            // The cap reader re-emits after a game from another device is ingested (or the day rolls over).
            cap.remaining.value = Money(8)
            val ready = assertIs<GamesHubUiState.Ready>(awaitItem())
            assertEquals(Money(8), ready.remaining)
            assertFalse(ready.capReached)
            cap.remaining.value = Money(50)
            assertEquals(Money(50), assertIs<GamesHubUiState.Ready>(awaitItem()).remaining)
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
