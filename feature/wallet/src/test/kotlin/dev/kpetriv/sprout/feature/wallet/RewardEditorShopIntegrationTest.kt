package dev.kpetriv.sprout.feature.wallet

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import dev.kpetriv.sprout.core.database.HouseholdFiatCurrencyProvider
import dev.kpetriv.sprout.core.database.LocaleFiatCurrencyProvider
import dev.kpetriv.sprout.core.database.TestHousehold
import dev.kpetriv.sprout.core.database.usecase.ManualAdjustmentResult
import dev.kpetriv.sprout.core.database.usecase.PurchaseResult
import dev.kpetriv.sprout.core.domainhousehold.Reward
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.RewardPurchased
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The reward editor against a real Room household ([TestHousehold], a parent signed in to a synced household): what it
 * saves through `RoomRewardRepository` is in the kid's shop ([ShopViewModel]) and `PurchaseRewardUseCase` can buy it
 * (the kid's write passes the catalogue check); a hidden reward and one for another kid are not in the shop. With the
 * kid's shop open on a reward, a parent's change to it takes effect before the kid confirms: a new price is what is
 * charged (never the old one), a hidden reward is not bought, and an edited reward is bought at its new values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RewardEditorShopIntegrationTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val household = TestHousehold(
        RuntimeEnvironment.getApplication(),
        sessionFor = TestHousehold.parentSession(HouseholdId("household-real"), "parent-1"),
    )
    private val fiat = HouseholdFiatCurrencyProvider(household.policies, LocaleFiatCurrencyProvider { Locale.UK })
    private val leo = KidId("kid-leo")
    private val mia = KidId("kid-mia")

    @After
    fun close() = household.close()

    private suspend fun setUpKids() {
        household.policies.ensure(household.gbp)
        household.addKid(leo.value)
        household.addKid(mia.value, age = 6)
        // £10 for Leo, so he can afford what the parent adds.
        val funded = household.adjust(leo, Money(1_000), "Birthday money") { error("no parent key, no prompt") }
        assertEquals(AppendResult.Accepted, (funded as ManualAdjustmentResult.Unsigned).result)
    }

    /** Fills a new reward in the editor with [events] and saves it. */
    private suspend fun addReward(vararg events: RewardEditorEvent) = runEditor(null, events.toList())

    /** Opens reward [id] in the editor, applies [events] and saves it. */
    private suspend fun editReward(id: RewardId, vararg events: RewardEditorEvent) = runEditor(id, events.toList())

    private suspend fun runEditor(id: RewardId?, events: List<RewardEditorEvent>) {
        val args = id?.let { mapOf(RewardEditorViewModel.REWARD_ID_KEY to it.value) }.orEmpty()
        val editor =
            RewardEditorViewModel(SavedStateHandle(args), household.rewards, household.kids, fiat, household.authors)
        editor.state.first { !it.isLoading }
        events.forEach(editor::onEvent)
        editor.onEvent(RewardEditorEvent.Save)
        assertTrue(editor.state.first { it.saved || it.saveFailed }.saved)
    }

    private suspend fun onlyReward(): Reward = household.rewards.observeRewards(includeInactive = true).first().single()

    private suspend fun purchases(kidId: KidId): List<RewardPurchased> =
        household.ledger.observeEvents(kidId, LIMIT).first().filterIsInstance<RewardPurchased>()

    /** [kidId]'s shop, kept subscribed (as on screen) until [block] ends. */
    private suspend fun TestScope.withLiveShop(kidId: KidId, block: suspend (ShopViewModel) -> Unit) {
        val shop = shop(kidId)
        val screen = launch(Dispatchers.Unconfined) { shop.state.collect {} }
        try {
            block(shop)
        } finally {
            screen.cancel()
        }
    }

    private suspend fun ShopViewModel.ready(until: (ShopUiState.Ready) -> Boolean): ShopUiState.Ready =
        state.first { it is ShopUiState.Ready && until(it) } as ShopUiState.Ready

    private fun shop(kidId: KidId) = ShopViewModel(
        savedStateHandle = SavedStateHandle(mapOf(ShopViewModel.KID_ID_KEY to kidId.value)),
        dataSource = RoomWalletDataSource(household),
        ledgerRepository = household.ledger,
        rewards = household.rewards,
        allowances = household.allowances,
        purchaser = RewardPurchaser(household.purchase::invoke),
        formatter = MoneyFormatter(Locale.UK),
        calendar = household.calendar,
    )

    private suspend fun shopTitles(kidId: KidId): List<String> =
        (shop(kidId).state.first { it is ShopUiState.Ready } as ShopUiState.Ready).items.map { it.title }

    @Test
    fun `a reward the editor saves is in the kid's shop and the kid can buy it`() = runTest {
        setUpKids()
        addReward(
            RewardEditorEvent.TitleChanged("Tablet time"),
            RewardEditorEvent.KindChanged(RewardKind.SCREEN_TIME),
            RewardEditorEvent.PriceChanged("2.50"),
            RewardEditorEvent.MinutesChanged("30"),
            RewardEditorEvent.AllKidsChanged(false),
            RewardEditorEvent.KidToggled(leo),
        )
        val reward = household.rewards.observeRewards(includeInactive = true).first().single()
        assertEquals(HouseholdId("household-real"), reward.householdId)
        assertEquals(household.gbp, reward.currency)

        assertEquals(listOf("Tablet time"), shopTitles(leo))

        val bought = household.purchase(leo, reward.id)
        assertIs<PurchaseResult.Purchased>(bought, "the kid's purchase matches the catalogue")
        val purchase = purchases(leo).single()
        assertEquals(Money(250), purchase.amount)
        assertEquals(Money(30), purchase.minutesGranted)
    }

    @Test
    fun `a reward saved hidden is not in the shop and cannot be bought`() = runTest {
        setUpKids()
        addReward(
            RewardEditorEvent.TitleChanged("Ice cream"),
            RewardEditorEvent.PriceChanged("1"),
            RewardEditorEvent.ActiveChanged(false),
        )
        addReward(RewardEditorEvent.TitleChanged("Sticker pack"), RewardEditorEvent.PriceChanged("0.50"))

        assertEquals(listOf("Sticker pack"), shopTitles(leo))
        val hidden = household.rewards.observeRewards(includeInactive = true).first().single { !it.active }
        assertEquals(PurchaseResult.Unavailable, household.purchase(leo, hidden.id))
    }

    @Test
    fun `a price change while the kid's confirm dialog is open charges the new price, never the old one`() = runTest {
        setUpKids()
        addReward(RewardEditorEvent.TitleChanged("Ice cream"), RewardEditorEvent.PriceChanged("2"))
        val id = onlyReward().id
        withLiveShop(leo) { shop ->
            shop.ready { state -> state.items.any { it.rewardId == id } }
            shop.onEvent(ShopEvent.Select(id))
            assertEquals(id, shop.ready { it.selected != null }.selected?.rewardId)

            editReward(id, RewardEditorEvent.PriceChanged("3"))
            shop.onEvent(ShopEvent.ConfirmPurchase)

            // Confirm re-resolves the selection and the purchase reads the reward as it is now: always the new price.
            assertIs<ShopMessage.Purchased>(shop.ready { it.message != null }.message)
            assertEquals(listOf(Money(300)), purchases(leo).map { it.amount }, "the new price, never the old one")
        }
    }

    @Test
    fun `hiding a reward while the kid's confirm dialog is open buys nothing`() = runTest {
        setUpKids()
        addReward(RewardEditorEvent.TitleChanged("Ice cream"), RewardEditorEvent.PriceChanged("2"))
        val id = onlyReward().id
        withLiveShop(leo) { shop ->
            shop.ready { state -> state.items.any { it.rewardId == id } }
            shop.onEvent(ShopEvent.Select(id))
            shop.ready { it.selected != null }

            editReward(id, RewardEditorEvent.ActiveChanged(false))
            val hidden = shop.ready { it.items.isEmpty() }
            assertEquals(null, hidden.selected, "the dialog closes with the reward gone")
            shop.onEvent(ShopEvent.ConfirmPurchase)

            val after = shop.ready { !it.isPurchasing }
            assertEquals(null, after.selected)
            assertTrue(after.message == null || after.message is ShopMessage.Failed)
            assertEquals(emptyList(), purchases(leo))
            assertEquals(PurchaseResult.Unavailable, household.purchase(leo, id))
        }
    }

    @Test
    fun `an existing reward edited in price, kind and minutes is bought at the new values`() = runTest {
        setUpKids()
        addReward(RewardEditorEvent.TitleChanged("Treat"), RewardEditorEvent.PriceChanged("2"))
        val id = onlyReward().id

        editReward(
            id,
            RewardEditorEvent.TitleChanged("Tablet time"),
            RewardEditorEvent.KindChanged(RewardKind.SCREEN_TIME),
            RewardEditorEvent.PriceChanged("3"),
            RewardEditorEvent.MinutesChanged("45"),
        )
        assertEquals(id, onlyReward().id, "edited in place")

        val bought = household.purchase(leo, id)
        assertIs<PurchaseResult.Purchased>(bought, "the re-saved reward passes the catalogue check")
        assertTrue(bought.timeCreditId != null, "its minutes are credited")
        val purchase = purchases(leo).single()
        assertEquals(Money(300), purchase.amount)
        assertEquals(Money(45), purchase.minutesGranted)
    }

    @Test
    fun `a reward for another kid is not in this kid's shop`() = runTest {
        setUpKids()
        addReward(
            RewardEditorEvent.TitleChanged("Trip to the zoo"),
            RewardEditorEvent.KindChanged(RewardKind.OUTING),
            RewardEditorEvent.PriceChanged("5"),
            RewardEditorEvent.AllKidsChanged(false),
            RewardEditorEvent.KidToggled(mia),
        )

        assertEquals(emptyList(), shopTitles(leo))
        assertEquals(listOf("Trip to the zoo"), shopTitles(mia))
        val zoo = household.rewards.observeRewards(includeInactive = true).first().single()
        assertEquals(PurchaseResult.Unavailable, household.purchase(leo, zoo.id))
    }

    private companion object {
        const val LIMIT = 50
    }
}

/** The wallet reads over the household's repositories, as the app's `RepositoryWalletDataSource` binds them. */
private class RoomWalletDataSource(private val household: TestHousehold) : WalletDataSource {
    override fun observeKid(kidId: KidId): Flow<Kid?> =
        household.kids.observeKids().map { kids -> kids.firstOrNull { it.id == kidId } }

    override fun accountsFor(kidId: KidId) = household.accounts.accountsFor(kidId)
}
