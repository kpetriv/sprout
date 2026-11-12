package dev.kpetriv.sprout.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.database.SavingsGoalRepository
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.MAX_TITLE_LENGTH
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import dev.kpetriv.sprout.core.ui.MinorUnitsParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The savings goals section for the selected kid. */
data class SavingsGoalsUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val kids: List<Kid> = emptyList(),
    val selectedKidId: KidId? = null,
    val currency: Currency.Fiat? = null,
    val goals: List<SavingsGoal> = emptyList(),
    val editor: GoalEditorUi? = null,
    val message: SettingsMessage? = null,
)

/** The add/edit goal sheet. [goalId] is null when adding. */
data class GoalEditorUi(
    val goalId: GoalId?,
    val kidId: KidId,
    val currency: Currency.Fiat,
    val title: String,
    val targetText: String,
    val titleError: NameError? = null,
    val targetError: AmountError? = null,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
)

sealed interface SavingsGoalsEvent {
    data class SelectKid(val kidId: KidId) : SavingsGoalsEvent

    data object AddGoal : SavingsGoalsEvent

    data class EditGoal(val goalId: GoalId) : SavingsGoalsEvent

    data class TitleChanged(val title: String) : SavingsGoalsEvent

    data class TargetChanged(val text: String) : SavingsGoalsEvent

    data object Save : SavingsGoalsEvent

    /** Deletes the goal being edited; the savings stay in the account. */
    data object Delete : SavingsGoalsEvent

    data object Dismiss : SavingsGoalsEvent

    data object MessageShown : SavingsGoalsEvent
}

/**
 * Savings goals per kid: title (at most [MAX_TITLE_LENGTH]) and a positive target in the kid's savings currency,
 * written through [SavingsGoalRepository] (outbox + audit). The first kid is selected until the parent picks one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SavingsGoalsViewModel @Inject constructor(
    private val kids: KidRepository,
    private val accounts: AccountRepository,
    private val goals: SavingsGoalRepository,
    private val household: HouseholdContext,
    private val calendar: HouseholdCalendar,
) : ViewModel() {
    private val selected = MutableStateFlow<KidId?>(null)
    private val local = MutableStateFlow(SavingsGoalsUiState())

    val state: StateFlow<SavingsGoalsUiState> = combine(content(), local) { content, localState ->
        val ready = content as? Load.Ready
        localState.copy(
            isLoading = content is Load.Loading,
            loadFailed = content is Load.Failed,
            kids = ready?.value?.kids.orEmpty(),
            selectedKidId = ready?.value?.selected,
            currency = ready?.value?.currency,
            goals = ready?.value?.goals.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SavingsGoalsUiState())

    fun onEvent(event: SavingsGoalsEvent) {
        when (event) {
            is SavingsGoalsEvent.SelectKid -> selected.value = event.kidId
            SavingsGoalsEvent.AddGoal -> openNew()
            is SavingsGoalsEvent.EditGoal -> openExisting(event.goalId)
            is SavingsGoalsEvent.TitleChanged -> updateEditor { it.copy(title = event.title, titleError = null) }
            is SavingsGoalsEvent.TargetChanged -> updateEditor { it.copy(targetText = event.text, targetError = null) }
            SavingsGoalsEvent.Save -> save()
            SavingsGoalsEvent.Delete -> delete()
            SavingsGoalsEvent.Dismiss -> local.update { it.copy(editor = null) }
            SavingsGoalsEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private data class Content(
        val kids: List<Kid>,
        val selected: KidId?,
        val currency: Currency.Fiat?,
        val goals: List<SavingsGoal>,
    )

    private fun content(): Flow<Load<Content>> = combine(kids.observeKids(), selected) { list, choice ->
        list to (list.firstOrNull { it.id == choice } ?: list.firstOrNull())?.id
    }.flatMapLatest { (list, kidId) ->
        if (kidId == null) {
            flowOf(Content(list, null, null, emptyList()))
        } else {
            combine(goals.observeGoals(kidId), savingsCurrency(kidId)) { goalList, currency ->
                Content(list, kidId, currency, goalList)
            }
        }
    }
        .map<Content, Load<Content>> { Load.Ready(it) }
        .onStart { emit(Load.Loading) }
        .catch { emit(Load.Failed) }

    /** The currency of [kidId]'s SAVINGS money account, or the household's when it has none yet. */
    private fun savingsCurrency(kidId: KidId): Flow<Currency.Fiat> = accounts.accountsFor(kidId).flatMapLatest { list ->
        val savings = list.firstOrNull { it.kind == AccountKind.SAVINGS && it.currency is Currency.Fiat }
        val currency = savings?.currency as? Currency.Fiat
        if (currency != null) flowOf(currency) else household.observeFiatCurrency()
    }

    private fun openNew() {
        val current = state.value
        val kidId = current.selectedKidId ?: return
        val currency = current.currency ?: return
        local.update { it.copy(editor = GoalEditorUi(null, kidId, currency, "", "")) }
    }

    private fun openExisting(goalId: GoalId) {
        val current = state.value
        val goal = current.goals.firstOrNull { it.id == goalId } ?: return
        val currency = current.currency ?: return
        val target = MinorUnitsParser.toInputText(goal.target.minorUnits, currency.fractionDigits)
        local.update { it.copy(editor = GoalEditorUi(goal.id, goal.kidId, currency, goal.title, target)) }
    }

    private fun save() {
        val editor = local.value.editor?.takeUnless { it.isSaving } ?: return
        val title = editor.title.trim()
        val titleError = validateName(title, MAX_TITLE_LENGTH)
        val (target, targetError) = parsePositiveAmount(editor.targetText, editor.currency, MAX_TARGET)
        if (titleError != null || target == null) {
            updateEditor { it.copy(titleError = titleError, targetError = targetError) }
        } else {
            persist(editor, title, target)
        }
    }

    private fun persist(editor: GoalEditorUi, title: String, target: Long) {
        updateEditor { it.copy(isSaving = true, saveFailed = false) }
        viewModelScope.launch {
            val saved = attempt {
                val existing = editor.goalId?.let { goals.get(it) }
                val goal = existing?.copy(title = title, target = Money(target)) ?: SavingsGoal(
                    id = GoalId(UUID.randomUUID().toString()),
                    kidId = editor.kidId,
                    title = title,
                    target = Money(target),
                    createdOn = calendar.today(),
                )
                goals.upsert(goal)
            }
            finish(saved, SettingsMessage.SAVED)
        }
    }

    private fun delete() {
        val goalId = local.value.editor?.takeUnless { it.isSaving }?.goalId ?: return
        updateEditor { it.copy(isSaving = true, saveFailed = false) }
        viewModelScope.launch {
            var deleted = false
            val ok = attempt { deleted = goals.delete(goalId) }
            finish(ok && deleted, SettingsMessage.GOAL_DELETED)
        }
    }

    private fun finish(success: Boolean, message: SettingsMessage) = local.update { current ->
        if (success) {
            current.copy(editor = null, message = message)
        } else {
            current.copy(editor = current.editor?.copy(isSaving = false, saveFailed = true))
        }
    }

    private fun updateEditor(transform: (GoalEditorUi) -> GoalEditorUi) =
        local.update { current -> current.editor?.let { current.copy(editor = transform(it)) } ?: current }

    internal companion object {
        val MAX_TARGET: Long = LedgerPolicy.DEFAULT_MAX_AMOUNT.minorUnits
    }
}
