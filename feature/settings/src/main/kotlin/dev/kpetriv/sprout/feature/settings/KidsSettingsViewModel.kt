package dev.kpetriv.sprout.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kpetriv.sprout.core.database.AccountRepository
import dev.kpetriv.sprout.core.database.KidRepository
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.MAX_FIRST_NAME_LENGTH
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The kids section: every active kid, and the add/edit sheet. */
data class KidsSettingsUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val kids: List<KidRowUi> = emptyList(),
    val editor: KidEditorUi? = null,
    val message: SettingsMessage? = null,
)

/** One kid in the list. [suggestedTier] is what their age suggests; [Kid.uiTier] differs when overridden. */
data class KidRowUi(val kid: Kid, val ageYears: Int, val suggestedTier: KidUiTier) {
    val isTierOverridden: Boolean get() = kid.uiTier != suggestedTier
}

/**
 * The add/edit kid sheet.
 *
 * @property kidId null when adding a kid.
 * @property tierChosen the parent picked [tier] instead of following [suggestedTier].
 * @property suggestedTier from the birth month's [AgeBand]; null while the birth month is in the future.
 * @property years the birth years offered, newest first.
 */
data class KidEditorUi(
    val kidId: KidId?,
    val firstName: String,
    val birthYear: Int,
    val birthMonth: Int,
    val tier: KidUiTier,
    val tierChosen: Boolean,
    val suggestedTier: KidUiTier?,
    val years: List<Int>,
    val nameError: NameError? = null,
    val birthInFuture: Boolean = false,
    val isSaving: Boolean = false,
    val saveFailed: Boolean = false,
) {
    val isNew: Boolean get() = kidId == null
}

/** Why a first name or title was refused. */
enum class NameError {
    REQUIRED,
    TOO_LONG,
}

sealed interface KidsSettingsEvent {
    data object AddKid : KidsSettingsEvent

    data class EditKid(val kidId: KidId) : KidsSettingsEvent

    data class NameChanged(val name: String) : KidsSettingsEvent

    data class BirthChanged(val year: Int, val month: Int) : KidsSettingsEvent

    /** The parent picked [tier] (an override when it differs from the suggestion). */
    data class TierChosen(val tier: KidUiTier) : KidsSettingsEvent

    data object Save : KidsSettingsEvent

    /** Archive the kid being edited; their ledger history stays. */
    data object Archive : KidsSettingsEvent

    data object DismissEditor : KidsSettingsEvent

    data object MessageShown : KidsSettingsEvent
}

/**
 * Kids & tiers. Edits go through [KidRepository] (audited); a new kid also gets its default accounts through
 * [AccountRepository.ensureDefaultAccounts] in the household currency.
 */
@HiltViewModel
class KidsSettingsViewModel @Inject constructor(
    private val kids: KidRepository,
    private val accounts: AccountRepository,
    private val household: HouseholdContext,
    private val calendar: HouseholdCalendar,
) : ViewModel() {
    private val local = MutableStateFlow(KidsSettingsUiState())

    val state: StateFlow<KidsSettingsUiState> = combine(rows(), local) { rows, localState ->
        localState.copy(
            isLoading = rows is Load.Loading,
            loadFailed = rows is Load.Failed,
            kids = (rows as? Load.Ready)?.value.orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), KidsSettingsUiState())

    fun onEvent(event: KidsSettingsEvent) {
        when (event) {
            KidsSettingsEvent.AddKid -> openEditor(null)
            is KidsSettingsEvent.EditKid -> openEditor(event.kidId)
            is KidsSettingsEvent.NameChanged -> updateEditor { it.copy(firstName = event.name, nameError = null) }
            is KidsSettingsEvent.BirthChanged -> updateEditor { it.withBirth(event.year, event.month) }
            is KidsSettingsEvent.TierChosen -> updateEditor { it.copy(tier = event.tier, tierChosen = true) }
            KidsSettingsEvent.Save -> save()
            KidsSettingsEvent.Archive -> archive()
            KidsSettingsEvent.DismissEditor -> local.update { it.copy(editor = null) }
            KidsSettingsEvent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private fun rows() = kids.observeKids()
        .map<List<Kid>, Load<List<KidRowUi>>> { list ->
            val month = calendar.today().yearMonth
            Load.Ready(
                list.map { kid ->
                    val birth = minOf(kid.birthYearMonth, month)
                    KidRowUi(kid, birth.yearsUntil(month), AgeBand.of(birth, month).suggestedTier)
                },
            )
        }
        .onStart { emit(Load.Loading) }
        .catch { emit(Load.Failed) }

    private fun openEditor(kidId: KidId?) {
        viewModelScope.launch {
            val today = calendar.today().yearMonth
            val years = (today.year downTo today.year - BIRTH_YEARS_OFFERED + 1).toList()
            val kid = kidId?.let { id -> state.value.kids.firstOrNull { it.kid.id == id }?.kid ?: kids.getKid(id) }
            val editor = if (kid == null) {
                val birth = today.plusMonths(-DEFAULT_AGE_MONTHS)
                val suggested = AgeBand.of(birth, today).suggestedTier
                KidEditorUi(null, "", birth.year, birth.month, suggested, false, suggested, years)
            } else {
                val suggested = suggestedFor(kid.birthYearMonth, today)
                val withKidYear = (years + kid.birthYearMonth.year).distinct().sortedDescending()
                KidEditorUi(
                    kidId = kid.id,
                    firstName = kid.firstName,
                    birthYear = kid.birthYearMonth.year,
                    birthMonth = kid.birthYearMonth.month,
                    tier = kid.uiTier,
                    tierChosen = kid.uiTier != suggested,
                    suggestedTier = suggested,
                    years = withKidYear,
                )
            }
            local.update { it.copy(editor = editor) }
        }
    }

    private fun KidEditorUi.withBirth(year: Int, month: Int): KidEditorUi {
        val suggested = suggestedFor(YearMonth(year, month), calendar.today().yearMonth)
        return copy(
            birthYear = year,
            birthMonth = month,
            suggestedTier = suggested,
            tier = if (tierChosen || suggested == null) tier else suggested,
            birthInFuture = false,
        )
    }

    private fun save() {
        val editor = local.value.editor?.takeUnless { it.isSaving } ?: return
        val name = editor.firstName.trim()
        val nameError = validateName(name, MAX_FIRST_NAME_LENGTH)
        val birth = YearMonth(editor.birthYear, editor.birthMonth)
        val birthInFuture = birth > calendar.today().yearMonth
        if (nameError != null || birthInFuture) {
            updateEditor { it.copy(nameError = nameError, birthInFuture = birthInFuture) }
        } else {
            persist(editor, name, birth)
        }
    }

    private fun persist(editor: KidEditorUi, name: String, birth: YearMonth) {
        updateEditor { it.copy(isSaving = true, saveFailed = false) }
        viewModelScope.launch {
            val existing = editor.kidId?.let { kids.getKid(it) }
            val saved = attempt {
                if (existing != null) {
                    kids.upsert(existing.copy(firstName = name, birthYearMonth = birth, uiTier = editor.tier))
                } else {
                    val kid = Kid(
                        id = KidId(UUID.randomUUID().toString()),
                        householdId = household.householdId(),
                        firstName = name,
                        avatarId = DEFAULT_AVATAR,
                        birthYearMonth = birth,
                        uiTier = editor.tier,
                    )
                    kids.upsert(kid)
                    accounts.ensureDefaultAccounts(kid.id, household.fiatCurrency())
                }
            }
            finish(saved, if (existing != null) SettingsMessage.SAVED else SettingsMessage.KID_ADDED)
        }
    }

    private fun archive() {
        val kidId = local.value.editor?.takeUnless { it.isSaving }?.kidId ?: return
        updateEditor { it.copy(isSaving = true, saveFailed = false) }
        viewModelScope.launch {
            var archived = false
            val ok = attempt { archived = kids.archive(kidId) }
            finish(ok && archived, SettingsMessage.KID_ARCHIVED)
        }
    }

    private fun finish(success: Boolean, message: SettingsMessage) = local.update { current ->
        if (success) {
            current.copy(editor = null, message = message)
        } else {
            current.copy(editor = current.editor?.copy(isSaving = false, saveFailed = true))
        }
    }

    private fun updateEditor(transform: (KidEditorUi) -> KidEditorUi) =
        local.update { current -> current.editor?.let { current.copy(editor = transform(it)) } ?: current }

    internal companion object {
        /** Birth years offered in the picker, counting back from this year. */
        const val BIRTH_YEARS_OFFERED = 19

        /** A new kid starts at eight years old, the middle of the range, until the parent picks the month. */
        const val DEFAULT_AGE_MONTHS = 8 * 12

        const val DEFAULT_AVATAR = "avatar-1"

        fun suggestedFor(birth: YearMonth, today: YearMonth): KidUiTier? =
            if (birth > today) null else AgeBand.of(birth, today).suggestedTier
    }
}

/** The first [NameError] of trimmed [text], or null when it is acceptable. */
internal fun validateName(text: String, max: Int): NameError? = when {
    text.isBlank() -> NameError.REQUIRED
    text.length > max -> NameError.TOO_LONG
    else -> null
}
