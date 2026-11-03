package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KidsSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val leo = Kid(KidId("leo"), HOUSEHOLD, "Leo", "avatar-2", YearMonth(2017, 6), KidUiTier.SPROUT)
    private val zoe = Kid(KidId("zoe"), HOUSEHOLD, "Zoe", "avatar-3", YearMonth(2013, 9), KidUiTier.SPROUT)
    private val kids = FakeKidRepository(leo, zoe)
    private val accounts = FakeAccountRepository()

    private fun TestScope.viewModel(): KidsSettingsViewModel {
        val vm = KidsSettingsViewModel(kids, accounts, householdContext(), fixedToday())
        backgroundScope.launch(main.dispatcher) { vm.state.collect {} }
        return vm
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(main.dispatcher) { block() }

    @Test
    fun `lists kids with their age and flags a tier that differs from the suggestion`() = test {
        val state = viewModel().state.value

        assertFalse(state.isLoading)
        val (leoRow, zoeRow) = state.kids
        assertEquals(9, leoRow.ageYears)
        assertEquals(KidUiTier.SPROUT, leoRow.suggestedTier)
        assertFalse(leoRow.isTierOverridden)
        assertEquals(13, zoeRow.ageYears)
        assertEquals(KidUiTier.SAPLING, zoeRow.suggestedTier)
        assertTrue(zoeRow.isTierOverridden)
    }

    @Test
    fun `editing an overridden kid starts with the chosen tier and the suggestion`() = test {
        val vm = viewModel()
        vm.onEvent(KidsSettingsEvent.EditKid(zoe.id))

        val editor = assertNotNull(vm.state.value.editor)
        assertEquals("Zoe", editor.firstName)
        assertEquals(KidUiTier.SPROUT, editor.tier)
        assertTrue(editor.tierChosen)
        assertEquals(KidUiTier.SAPLING, editor.suggestedTier)
    }

    @Test
    fun `a new kid's tier follows the birth month until the parent picks one`() = test {
        val vm = viewModel()
        vm.onEvent(KidsSettingsEvent.AddKid)
        assertEquals(KidUiTier.SPROUT, vm.state.value.editor?.tier)

        vm.onEvent(KidsSettingsEvent.BirthChanged(2020, 5))
        assertEquals(KidUiTier.SEEDLING, vm.state.value.editor?.tier)
        assertEquals(KidUiTier.SEEDLING, vm.state.value.editor?.suggestedTier)

        vm.onEvent(KidsSettingsEvent.TierChosen(KidUiTier.SPROUT))
        vm.onEvent(KidsSettingsEvent.BirthChanged(2012, 1))
        assertEquals(KidUiTier.SPROUT, vm.state.value.editor?.tier)
        assertEquals(KidUiTier.SAPLING, vm.state.value.editor?.suggestedTier)
    }

    @Test
    fun `name and birth month are validated before anything is written`() = test {
        val vm = viewModel()
        vm.onEvent(KidsSettingsEvent.AddKid)

        vm.onEvent(KidsSettingsEvent.NameChanged("   "))
        vm.onEvent(KidsSettingsEvent.Save)
        assertEquals(NameError.REQUIRED, vm.state.value.editor?.nameError)

        vm.onEvent(KidsSettingsEvent.NameChanged("A".repeat(41)))
        vm.onEvent(KidsSettingsEvent.Save)
        assertEquals(NameError.TOO_LONG, vm.state.value.editor?.nameError)

        vm.onEvent(KidsSettingsEvent.NameChanged("A".repeat(40)))
        vm.onEvent(KidsSettingsEvent.BirthChanged(2026, 12))
        assertNull(vm.state.value.editor?.suggestedTier)
        vm.onEvent(KidsSettingsEvent.Save)
        assertTrue(vm.state.value.editor?.birthInFuture == true)
        assertNull(vm.state.value.editor?.nameError)

        assertEquals(2, kids.kids.value.size)
        assertTrue(accounts.ensured.isEmpty())
    }

    @Test
    fun `adding a kid saves the trimmed name and creates default accounts in the household currency`() = test {
        val vm = viewModel()
        vm.onEvent(KidsSettingsEvent.AddKid)
        vm.onEvent(KidsSettingsEvent.NameChanged("  Mia "))
        vm.onEvent(KidsSettingsEvent.BirthChanged(2020, 3))
        vm.onEvent(KidsSettingsEvent.Save)

        val mia = kids.kids.value.single { it.firstName == "Mia" }
        assertEquals(HOUSEHOLD, mia.householdId)
        assertEquals(YearMonth(2020, 3), mia.birthYearMonth)
        assertEquals(KidUiTier.SEEDLING, mia.uiTier)
        assertEquals(listOf(mia.id to GBP), accounts.ensured)
        assertNull(vm.state.value.editor)
        assertEquals(SettingsMessage.KID_ADDED, vm.state.value.message)
        assertEquals(3, vm.state.value.kids.size)
    }

    @Test
    fun `editing saves name, birth month and a tier override`() = test {
        val vm = viewModel()
        vm.onEvent(KidsSettingsEvent.EditKid(leo.id))
        vm.onEvent(KidsSettingsEvent.NameChanged("Leon"))
        vm.onEvent(KidsSettingsEvent.TierChosen(KidUiTier.SAPLING))
        vm.onEvent(KidsSettingsEvent.Save)

        val saved = kids.kids.value.single { it.id == leo.id }
        assertEquals("Leon", saved.firstName)
        assertEquals(KidUiTier.SAPLING, saved.uiTier)
        assertEquals(leo.avatarId, saved.avatarId)
        assertEquals(SettingsMessage.SAVED, vm.state.value.message)
        assertTrue(accounts.ensured.isEmpty())
    }

    @Test
    fun `archiving hides the kid`() = test {
        val vm = viewModel()
        vm.onEvent(KidsSettingsEvent.EditKid(leo.id))
        vm.onEvent(KidsSettingsEvent.Archive)

        assertEquals(listOf(zoe.id), vm.state.value.kids.map { it.kid.id })
        assertEquals(SettingsMessage.KID_ARCHIVED, vm.state.value.message)
    }

    @Test
    fun `a failed write keeps the sheet open with an error`() = test {
        kids.failWrites = true
        val vm = viewModel()
        vm.onEvent(KidsSettingsEvent.EditKid(leo.id))
        vm.onEvent(KidsSettingsEvent.Save)

        val editor = assertNotNull(vm.state.value.editor)
        assertTrue(editor.saveFailed)
        assertFalse(editor.isSaving)
        assertNull(vm.state.value.message)
    }
}
