package dev.kpetriv.sprout.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.domainhousehold.AllowanceSchedule
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.InterestCalculator
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.AllowanceCadence
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DayOfWeek
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.InterestPolicy
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.ScheduleId
import dev.kpetriv.sprout.core.model.YearMonth

/** Fixed sample states for previews and screenshot tests. No clock, no random ids, no real people. */
@Suppress("MagicNumber") // Sample birth months, ages and rates are the data itself.
object SettingsPreviewData {
    val household = HouseholdId("preview-household")
    val gbp = Currency.Fiat("GBP")

    val mia = Kid(KidId("kid-mia"), household, "Mia", "avatar-1", YearMonth(2020, 3), KidUiTier.SEEDLING)
    val leo = Kid(KidId("kid-leo"), household, "Leo", "avatar-2", YearMonth(2017, 6), KidUiTier.SPROUT)
    val zoe = Kid(KidId("kid-zoe"), household, "Zoe", "avatar-3", YearMonth(2013, 9), KidUiTier.SPROUT)

    /** About on a tagged release build, with a short licence list. */
    val about: AboutUiState = AboutUiState(
        versionName = "1.0.0",
        versionCode = 128,
        hasPrivacyPolicy = true,
        hasSecurityPolicy = true,
        libraries = listOf(
            OpenSourceLibrary(
                name = "Activity",
                module = "androidx.activity:activity",
                version = "1.13.0",
                url = "https://developer.android.com/jetpack/androidx/releases/activity",
                licences = listOf(LicenceLink("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0")),
            ),
            OpenSourceLibrary(
                name = "SQLCipher for Android",
                module = "net.zetetic:sqlcipher-android",
                version = "4.20.0",
                url = "https://www.zetetic.net/sqlcipher/",
                licences = listOf(LicenceLink("BSD-3-Clause", "https://www.zetetic.net/sqlcipher/license/")),
            ),
            OpenSourceLibrary(
                name = "Timber",
                module = "com.jakewharton.timber:timber",
                version = "5.0.1",
                url = null,
                licences = emptyList(),
            ),
        ),
    )

    /** A phone that supports Gemini Nano before the parent agreed to the download. */
    fun ai(status: AiModelStatus = AiModelStatus.DOWNLOADABLE): AiSettingsUiState = AiSettingsUiState(
        isLoading = false,
        settings = AiSettings(status = status, enabled = true, downloadConsent = false),
    )

    fun kids(): KidsSettingsUiState = KidsSettingsUiState(
        isLoading = false,
        kids = listOf(
            KidRowUi(mia, 6, KidUiTier.SEEDLING),
            KidRowUi(leo, 9, KidUiTier.SPROUT),
            KidRowUi(zoe, 13, KidUiTier.SAPLING),
        ),
    )

    /** Leo weekly, Mia paused, Zoe none; the default game limit. */
    fun allowance(): AllowanceSettingsUiState {
        val leoWeekly = AllowanceSchedule(
            id = ScheduleId("leo-weekly"),
            kidId = leo.id,
            accountId = AccountId("leo-main"),
            amount = Money(500),
            currency = gbp,
            cadence = AllowanceCadence.Weekly(DayOfWeek.SATURDAY),
            startsOn = CalendarDate(2026, 10, 3),
        )
        val miaMonthly = leoWeekly.copy(
            id = ScheduleId("mia-monthly"),
            kidId = mia.id,
            accountId = AccountId("mia-main"),
            amount = Money(1_000),
            cadence = AllowanceCadence.Monthly(1),
            active = false,
        )
        return AllowanceSettingsUiState(
            isLoading = false,
            rows = listOf(
                AllowanceRowUi(mia, miaMonthly, null),
                AllowanceRowUi(leo, leoWeekly, CalendarDate(2026, 11, 7)),
                AllowanceRowUi(zoe, null, null),
            ),
            gameCap = GameCapUi(capMinor = HouseholdRulesState.DEFAULT_DAILY_GAME_CAP_MINOR, isDefault = true, gbp),
        )
    }

    fun allowanceEditor(): AllowanceEditorUi = AllowanceEditorUi(
        kidId = leo.id,
        kidName = "Leo",
        scheduleId = ScheduleId("leo-weekly"),
        currency = gbp,
        amountText = "5.00",
        cadence = CadenceKind.WEEKLY,
        dayOfWeek = DayOfWeek.SATURDAY,
        dayOfMonth = 1,
        startsOn = CalendarDate(2026, 10, 3),
        active = true,
        nextDue = CalendarDate(2026, 11, 7),
    )

    fun kidEditor(isNew: Boolean = false): KidEditorUi = KidEditorUi(
        kidId = if (isNew) null else zoe.id,
        firstName = if (isNew) "" else "Zoe",
        birthYear = if (isNew) 2020 else 2013,
        birthMonth = if (isNew) 1 else 9,
        tier = if (isNew) KidUiTier.SEEDLING else KidUiTier.SPROUT,
        tierChosen = !isNew,
        suggestedTier = if (isNew) KidUiTier.SEEDLING else KidUiTier.SAPLING,
        years = (2026 downTo 2008).toList(),
    )

    fun goals(): SavingsGoalsUiState = SavingsGoalsUiState(
        isLoading = false,
        kids = listOf(mia, leo, zoe),
        selectedKidId = leo.id,
        currency = gbp,
        goals = listOf(
            SavingsGoal(GoalId("goal-bike"), leo.id, "New bike", Money(12_000), CalendarDate(2026, 9, 1)),
            SavingsGoal(
                GoalId("goal-lego"),
                leo.id,
                "Lego set",
                Money(4_500),
                CalendarDate(2026, 6, 1),
                achievedOn = CalendarDate(2026, 10, 12),
            ),
        ),
    )

    fun goalEditor(): GoalEditorUi = GoalEditorUi(
        goalId = null,
        kidId = leo.id,
        currency = gbp,
        title = "Football boots",
        targetText = "",
        targetError = AmountError.REQUIRED,
    )

    /** The data section of a synced household keeping chat for 90 days. */
    fun data(retentionDays: Int? = 90): DataUiState =
        DataUiState(householdId = HouseholdId("hh7f3k2q9xabc"), retention = RetentionUi(days = retentionDays))

    fun interest(rateText: String = "2.50", accrualDay: Int = 1): InterestSettingsUiState {
        val parsed = InterestRate.parse(rateText)
        val bps = (parsed as? InterestRate.Parsed.Valid)?.basisPoints
        val principal = Money(10_000)
        return InterestSettingsUiState(
            isLoading = false,
            currency = gbp,
            saved = InterestPolicy(household, annualRateBasisPoints = 250, accrualDay = 1),
            rateText = rateText,
            accrualDay = accrualDay,
            rateError = (parsed as? InterestRate.Parsed.Invalid)?.error,
            preview = bps?.let {
                InterestPreview(
                    principal,
                    InterestCalculator.accrue(
                        principal,
                        it,
                        InterestSettingsUiState.PREVIEW_DAYS,
                    ),
                )
            },
        )
    }

    fun security(
        availability: ParentAuthAvailability = ParentAuthAvailability.STRONG_BIOMETRIC_AVAILABLE,
        hasParentKey: Boolean = true,
        pinError: PinChangeError? = null,
        keyNeedsRenewal: Boolean = false,
        signingPin: SigningPinUi? = null,
        changePin: ChangePinUi = ChangePinUi(),
        keyNotRegisteredWithHousehold: Boolean = false,
    ): ParentSecurityUiState = ParentSecurityUiState(
        availability = availability,
        policy = SignaturePolicyUi(hasParentKey = hasParentKey, requireParentSignature = hasParentKey),
        keyNeedsRenewal = keyNeedsRenewal,
        keyNotRegisteredWithHousehold = keyNotRegisteredWithHousehold,
        lockWhenLeaving = true,
        changePin = changePin,
        pinError = pinError,
        signingPin = signingPin,
    )
}

@Preview
@Composable
private fun SettingsHubPreview() {
    SproutTheme { SettingsScreen(onOpen = {}, onBack = {}) }
}

@Preview
@Composable
private fun InterestSettingsPreview() {
    SproutTheme { InterestSettingsScreen(SettingsPreviewData.interest(), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun ParentSecurityPreview() {
    SproutTheme {
        ParentSecurityScreen(
            SettingsPreviewData.security(ParentAuthAvailability.NOT_ENROLLED, hasParentKey = false),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun AboutPreview() {
    SproutTheme { AboutScreen(SettingsPreviewData.about, onEvent = {}, onOpenLicences = {}, onBack = {}) }
}
