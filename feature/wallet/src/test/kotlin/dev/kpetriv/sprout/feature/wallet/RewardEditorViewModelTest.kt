package dev.kpetriv.sprout.feature.wallet

import androidx.lifecycle.SavedStateHandle
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind
import dev.kpetriv.sprout.core.ui.MinorUnitsParser
import dev.kpetriv.sprout.feature.wallet.RewardEditorError.MINUTES_MISSING
import dev.kpetriv.sprout.feature.wallet.RewardEditorError.MINUTES_NOT_A_NUMBER
import dev.kpetriv.sprout.feature.wallet.RewardEditorError.MINUTES_NOT_POSITIVE
import dev.kpetriv.sprout.feature.wallet.RewardEditorError.MINUTES_OVER_A_DAY
import dev.kpetriv.sprout.feature.wallet.RewardEditorError.NO_KIDS_CHOSEN
import dev.kpetriv.sprout.feature.wallet.RewardEditorError.PRICE_MISSING
import dev.kpetriv.sprout.feature.wallet.RewardEditorError.PRICE_NOT_A_NUMBER
import dev.kpetriv.sprout.feature.wallet.RewardEditorError.PRICE_NOT_POSITIVE
import dev.kpetriv.sprout.feature.wallet.RewardEditorError.PRICE_TOO_HIGH
import dev.kpetriv.sprout.feature.wallet.RewardEditorError.PRICE_TOO_MANY_DECIMALS
import dev.kpetriv.sprout.feature.wallet.RewardEditorError.TITLE_BLANK
import dev.kpetriv.sprout.feature.wallet.RewardEditorError.TITLE_TOO_LONG
import dev.kpetriv.sprout.feature.wallet.RewardEditorEvent.ActiveChanged
import dev.kpetriv.sprout.feature.wallet.RewardEditorEvent.AllKidsChanged
import dev.kpetriv.sprout.feature.wallet.RewardEditorEvent.KidToggled
import dev.kpetriv.sprout.feature.wallet.RewardEditorEvent.KindChanged
import dev.kpetriv.sprout.feature.wallet.RewardEditorEvent.MinutesChanged
import dev.kpetriv.sprout.feature.wallet.RewardEditorEvent.PriceChanged
import dev.kpetriv.sprout.feature.wallet.RewardEditorEvent.Save
import dev.kpetriv.sprout.feature.wallet.RewardEditorEvent.TitleChanged
import dev.kpetriv.sprout.feature.wallet.RewardsTestData.leo
import dev.kpetriv.sprout.feature.wallet.RewardsTestData.mia
import dev.kpetriv.sprout.feature.wallet.RewardsTestData.reward
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RewardEditorViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val rewards = FakeRewardRepository()
    private val kids = FakeKidRepository(listOf(mia, leo))
    private val fiat = FakeFiatCurrencyProvider()

    private fun editor(rewardId: String? = null): RewardEditorViewModel = RewardEditorViewModel(
        SavedStateHandle(rewardId?.let { mapOf(RewardEditorViewModel.REWARD_ID_KEY to it) }.orEmpty()),
        rewards,
        kids,
        fiat,
        FakeSessionAuthorProvider(),
    )

    private fun RewardEditorViewModel.fill(vararg events: RewardEditorEvent) = events.forEach(::onEvent)

    /** The errors a save attempt with [events] reports (nothing is saved while there are any). */
    private fun errorsAfter(vararg events: RewardEditorEvent): Set<RewardEditorError> {
        val vm = editor()
        vm.fill(TitleChanged("Ice cream"), PriceChanged("2.50"), *events, Save)
        return vm.state.value.errors
    }

    private fun saved() = rewards.rewards.value.single()

    @Test
    fun `a new reward is saved with the form's fields, a fresh id and the household's currency`() {
        val vm = editor()
        assertTrue(vm.state.value.isNew)
        assertEquals(listOf("Mia", "Leo"), vm.state.value.kids.map { it.name })

        vm.fill(TitleChanged("  Ice cream  "), KindChanged(RewardKind.TREAT), PriceChanged("2.50"), Save)

        assertTrue(vm.state.value.saved)
        val reward = saved()
        assertEquals("Ice cream", reward.title)
        assertEquals(RewardKind.TREAT, reward.kind)
        assertEquals(Money(250), reward.cost)
        assertEquals(TestData.gbp, reward.currency)
        assertNull(reward.minutesGranted)
        assertTrue(reward.active)
        assertEquals(emptySet(), reward.forKids)
        assertEquals(RewardsTestData.household, reward.householdId)
        UUID.fromString(reward.id.value) // A random UUID, as the demo seed makes.
    }

    @Test
    fun `a new screen-time reward grants its minutes`() {
        val vm = editor()
        vm.fill(TitleChanged("Tablet"), KindChanged(RewardKind.SCREEN_TIME), PriceChanged("1"), MinutesChanged("30"))
        vm.onEvent(Save)

        assertEquals(30, saved().minutesGranted)
        assertEquals(Money(100), saved().cost)
    }

    @Test
    fun `the price is read in the household's currency`() {
        fiat.currency.value = Currency.Fiat("JPY")
        val vm = editor()
        vm.fill(TitleChanged("Ice cream"), PriceChanged("300"), Save)

        assertEquals(Currency.Fiat("JPY"), saved().currency)
        assertEquals(Money(300), saved().cost)
        assertEquals(setOf(PRICE_TOO_MANY_DECIMALS), errorsAfterWith("2.5"))
    }

    private fun errorsAfterWith(price: String): Set<RewardEditorError> {
        val vm = editor()
        vm.fill(TitleChanged("Ice cream"), PriceChanged(price), Save)
        return vm.state.value.errors
    }

    @Test
    fun `editing keeps the id, the household and every field it does not show`() {
        val other = HouseholdId("household-other")
        rewards.rewards.value = listOf(
            reward("tablet", "Tablet", RewardKind.SCREEN_TIME, cost = 150, minutes = 30, householdId = other),
        )
        val vm = editor("tablet")
        val loaded = vm.state.value
        assertFalse(loaded.isNew)
        assertEquals("Tablet", loaded.title)
        assertEquals(RewardKind.SCREEN_TIME, loaded.kind)
        assertEquals("1.50", loaded.priceText)
        assertEquals("30", loaded.minutesText)
        assertTrue(loaded.forAllKids)
        assertTrue(loaded.active)

        vm.fill(TitleChanged("Tablet time"), PriceChanged("2"), Save)

        val reward = saved()
        assertEquals(RewardId("tablet"), reward.id)
        assertEquals(other, reward.householdId, "the household of the reward, not re-read from the session")
        assertEquals("Tablet time", reward.title)
        assertEquals(Money(200), reward.cost)
        assertEquals(30, reward.minutesGranted)
    }

    @Test
    fun `a reward that is gone shows not found and cannot be saved`() {
        val vm = editor("nope")
        assertTrue(vm.state.value.notFound)
        vm.onEvent(Save)
        assertTrue(rewards.rewards.value.isEmpty())
    }

    @Test
    fun `errors show only after a save attempt and then follow the form`() {
        val vm = editor()
        vm.onEvent(TitleChanged(""))
        assertEquals(emptySet(), vm.state.value.errors)

        vm.onEvent(Save)
        assertEquals(setOf(TITLE_BLANK, PRICE_MISSING), vm.state.value.errors)
        assertTrue(rewards.rewards.value.isEmpty())

        vm.onEvent(TitleChanged("Ice cream"))
        assertEquals(setOf(PRICE_MISSING), vm.state.value.errors)
        vm.onEvent(PriceChanged("1"))
        assertEquals(emptySet(), vm.state.value.errors)
    }

    @Test
    fun `the title is required and at most 80 characters after trimming`() {
        assertEquals(setOf(TITLE_BLANK), errorsAfter(TitleChanged("   ")))
        assertEquals(emptySet(), errorsAfter(TitleChanged("a".repeat(80))))
        assertEquals(setOf(TITLE_TOO_LONG), errorsAfter(TitleChanged("a".repeat(81))))
        assertEquals(emptySet(), errorsAfter(TitleChanged(" " + "a".repeat(79) + " ")), "81 typed, 79 kept")

        val vm = editor()
        vm.onEvent(TitleChanged("a".repeat(200)))
        assertEquals(81, vm.state.value.title.length, "typing stops one past the limit, so the error shows")
    }

    @Test
    fun `the price must be a positive amount within the ledger's bound`() {
        assertEquals(setOf(PRICE_MISSING), errorsAfterWith(""))
        assertEquals(setOf(PRICE_NOT_POSITIVE), errorsAfterWith("0"))
        assertEquals(setOf(PRICE_NOT_POSITIVE), errorsAfterWith("0.00"))
        assertEquals(emptySet(), errorsAfterWith("0.01"))
        assertEquals(setOf(PRICE_NOT_A_NUMBER), errorsAfterWith("two"))
        assertEquals(setOf(PRICE_NOT_A_NUMBER), errorsAfterWith("-1"))
        assertEquals(setOf(PRICE_TOO_MANY_DECIMALS), errorsAfterWith("1.234"))

        val max = LedgerPolicy.DEFAULT_MAX_AMOUNT.minorUnits
        assertEquals(max, RewardEditorViewModel.MAX_PRICE_MINOR)
        assertEquals(emptySet(), errorsAfterWith(MinorUnitsText.of(max, 2)))
        assertEquals(setOf(PRICE_TOO_HIGH), errorsAfterWith(MinorUnitsText.of(max + 1, 2)))
    }

    @Test
    fun `the highest price is saved as exactly that many minor units`() {
        val vm = editor()
        vm.fill(TitleChanged("Car"), PriceChanged(MinorUnitsText.of(RewardEditorViewModel.MAX_PRICE_MINOR, 2)), Save)
        assertEquals(LedgerPolicy.DEFAULT_MAX_AMOUNT, saved().cost)
    }

    @Test
    fun `screen time needs a positive whole number of minutes within the bound`() {
        val screenTime = KindChanged(RewardKind.SCREEN_TIME)
        assertEquals(setOf(MINUTES_MISSING), errorsAfter(screenTime))
        assertEquals(setOf(MINUTES_NOT_POSITIVE), errorsAfter(screenTime, MinutesChanged("0")))
        assertEquals(emptySet(), errorsAfter(screenTime, MinutesChanged("1")))
        assertEquals(setOf(MINUTES_NOT_A_NUMBER), errorsAfter(screenTime, MinutesChanged("2.5")))
        assertEquals(setOf(MINUTES_NOT_A_NUMBER), errorsAfter(screenTime, MinutesChanged("half")))

        assertEquals(1_440L, RewardEditorViewModel.MAX_SCREEN_TIME_MINUTES)
        assertEquals(emptySet(), errorsAfter(screenTime, MinutesChanged("1440")))
        assertEquals(setOf(MINUTES_OVER_A_DAY), errorsAfter(screenTime, MinutesChanged("1441")))

        val max = RewardEditorViewModel.MAX_MINUTES
        assertEquals(minOf(LedgerPolicy.DEFAULT_MAX_AMOUNT.minorUnits, Int.MAX_VALUE.toLong()), max)
        assertTrue(RewardEditorViewModel.MAX_SCREEN_TIME_MINUTES <= max, "the day's cap is inside the ledger bound")
        listOf(max, max + 1, 9_999_999_999L, Long.MAX_VALUE).forEach { minutes ->
            assertEquals(setOf(MINUTES_OVER_A_DAY), errorsAfter(screenTime, MinutesChanged(minutes.toString())))
        }
        assertEquals(setOf(MINUTES_OVER_A_DAY), errorsAfter(screenTime, MinutesChanged("99999999999999999999")))
    }

    @Test
    fun `switching away from screen time clears the minutes, and back requires them again`() {
        val vm = editor()
        vm.fill(TitleChanged("Tablet"), PriceChanged("1"), KindChanged(RewardKind.SCREEN_TIME), MinutesChanged("30"))
        assertTrue(vm.state.value.showsMinutes)

        vm.onEvent(KindChanged(RewardKind.OUTING))
        assertFalse(vm.state.value.showsMinutes)
        assertEquals("", vm.state.value.minutesText)

        vm.onEvent(KindChanged(RewardKind.SCREEN_TIME))
        vm.onEvent(Save)
        assertEquals(setOf(MINUTES_MISSING), vm.state.value.errors)

        vm.onEvent(KindChanged(RewardKind.OTHER))
        assertEquals(emptySet(), vm.state.value.errors)
        vm.onEvent(Save)
        assertEquals(RewardKind.OTHER, saved().kind)
        assertNull(saved().minutesGranted)
    }

    @Test
    fun `an edited screen-time reward switched to a treat stops granting minutes`() {
        rewards.rewards.value = listOf(reward("tablet", "Tablet", RewardKind.SCREEN_TIME, cost = 150, minutes = 30))
        val vm = editor("tablet")
        vm.fill(KindChanged(RewardKind.TREAT), Save)
        assertEquals(RewardKind.TREAT, saved().kind)
        assertNull(saved().minutesGranted)
    }

    @Test
    fun `a reward is for all kids or for the chosen ones`() {
        val vm = editor()
        vm.fill(TitleChanged("Ice cream"), PriceChanged("1"), AllKidsChanged(false), Save)
        assertEquals(setOf(NO_KIDS_CHOSEN), vm.state.value.errors, "chosen kids with none chosen")

        vm.fill(KidToggled(mia.id), KidToggled(leo.id), KidToggled(leo.id), Save)
        assertEquals(setOf(mia.id), saved().forKids)

        val all = editor(saved().id.value)
        assertFalse(all.state.value.forAllKids)
        assertEquals(setOf(mia.id), all.state.value.chosenKids)
        all.fill(AllKidsChanged(true), Save)
        assertEquals(emptySet(), saved().forKids, "all kids is an empty set")
    }

    @Test
    fun `a chosen kid who left the household is dropped on save`() {
        val gone = KidId("kid-gone")
        rewards.rewards.value = listOf(reward("ice-cream", forKids = setOf(mia.id, gone)))
        val vm = editor("ice-cream")
        vm.onEvent(Save)
        assertEquals(setOf(mia.id), saved().forKids)
    }

    @Test
    fun `a kid removed while the editor is open is dropped too`() {
        val vm = editor()
        vm.fill(TitleChanged("Ice cream"), PriceChanged("1"), AllKidsChanged(false), KidToggled(mia.id))
        vm.onEvent(KidToggled(leo.id))
        kids.kids.value = listOf(leo)

        assertEquals(listOf("Leo"), vm.state.value.kids.map { it.name })
        vm.onEvent(Save)
        assertEquals(setOf(leo.id), saved().forKids)
    }

    @Test
    fun `only kids who left is an error, never every kid`() {
        rewards.rewards.value = listOf(reward("ice-cream", forKids = setOf(KidId("kid-gone"))))
        val vm = editor("ice-cream")
        vm.onEvent(Save)

        assertEquals(setOf(NO_KIDS_CHOSEN), vm.state.value.errors)
        assertEquals(setOf(KidId("kid-gone")), saved().forKids, "nothing was written")
    }

    @Test
    fun `hiding and showing a reward`() {
        rewards.rewards.value = listOf(reward("ice-cream"))
        editor("ice-cream").fill(ActiveChanged(false), Save)
        assertFalse(saved().active)

        val again = editor("ice-cream")
        assertFalse(again.state.value.active)
        again.fill(ActiveChanged(true), Save)
        assertTrue(saved().active)
    }

    @Test
    fun `a new reward can start hidden`() {
        editor().fill(TitleChanged("Surprise"), PriceChanged("5"), ActiveChanged(false), Save)
        assertFalse(saved().active)
    }

    @Test
    fun `a failed save says so and keeps the form`() {
        rewards.failWrites = true
        val vm = editor()
        vm.fill(TitleChanged("Ice cream"), PriceChanged("2.50"), Save)

        val failed = vm.state.value
        assertTrue(failed.saveFailed)
        assertFalse(failed.saved)
        assertFalse(failed.isSaving)
        assertEquals("Ice cream", failed.title)
        assertEquals("2.50", failed.priceText)
        assertTrue(rewards.rewards.value.isEmpty())

        vm.onEvent(RewardEditorEvent.SaveFailedShown)
        assertFalse(vm.state.value.saveFailed)
        rewards.failWrites = false
        vm.onEvent(Save)
        assertTrue(vm.state.value.saved)
        assertEquals("Ice cream", saved().title)
    }

    @Test
    fun `an edit keeps a no-decimal reward's own currency and amount, whatever the household's`() {
        val yen = Currency.Fiat("JPY")
        rewards.rewards.value = listOf(reward("ice-cream", cost = 500, currency = yen))
        val vm = editor("ice-cream")
        assertEquals(yen, vm.state.value.currency, "the label, decimals and parsing are the reward's")
        assertEquals("500", vm.state.value.priceText)

        vm.fill(TitleChanged("Big ice cream"), Save)

        assertEquals("Big ice cream", saved().title)
        assertEquals(yen, saved().currency)
        assertEquals(Money(500), saved().cost)
    }

    @Test
    fun `an edit keeps a two-decimal reward's own currency when the household's differs`() {
        fiat.currency.value = Currency.Fiat("USD") // The locale fallback before the first policy pull.
        rewards.rewards.value = listOf(reward("ice-cream", cost = 250, currency = TestData.gbp))
        val vm = editor("ice-cream")
        assertEquals("2.50", vm.state.value.priceText)

        vm.fill(TitleChanged("Ice lolly"), Save)

        assertEquals(TestData.gbp, saved().currency)
        assertEquals(Money(250), saved().cost)
    }

    @Test
    fun `a household currency change while an edit is open changes nothing`() {
        rewards.rewards.value = listOf(reward("ice-cream", cost = 250))
        val vm = editor("ice-cream")
        vm.onEvent(PriceChanged("3"))

        fiat.currency.value = Currency.Fiat("JPY")

        assertEquals(TestData.gbp, vm.state.value.currency)
        assertEquals("3", vm.state.value.priceText)
        vm.onEvent(Save)
        assertEquals(TestData.gbp, saved().currency)
        assertEquals(Money(300), saved().cost)
    }

    @Test
    fun `a new reward follows the household's currency, keeping the typed price`() {
        val vm = editor()
        vm.fill(TitleChanged("Ice cream"), PriceChanged("300"))

        fiat.currency.value = Currency.Fiat("JPY")

        assertEquals(Currency.Fiat("JPY"), vm.state.value.currency)
        assertEquals("300", vm.state.value.priceText)
        vm.onEvent(Save)
        assertEquals(Currency.Fiat("JPY"), saved().currency)
        assertEquals(Money(300), saved().cost)
    }

    @Test
    fun `the example price in the error suits the currency's decimals`() {
        assertEquals("250", examplePrice(0))
        assertEquals("2.5", examplePrice(1))
        assertEquals("2.50", examplePrice(2))
        assertEquals("2.500", examplePrice(3))
    }
}

/** Minor units as the text a parent types ("10000000000.00"), built without floating point. */
private object MinorUnitsText {
    fun of(minor: Long, fractionDigits: Int): String = MinorUnitsParser.toInputText(minor, fractionDigits)
}
