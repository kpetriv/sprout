package dev.kpetriv.sprout.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.InterestCalculator
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.InterestPolicy
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.YearMonth

/** Fixed sample states for previews and screenshot tests. No clock, no random ids, no real people. */
@Suppress("MagicNumber") // Sample birth months, ages and rates are the data itself.
object SettingsPreviewData {
    val household = HouseholdId("preview-household")
    val gbp = Currency.Fiat("GBP")

    val mia = Kid(KidId("kid-mia"), household, "Mia", "avatar-1", YearMonth(2020, 3), KidUiTier.SEEDLING)
    val leo = Kid(KidId("kid-leo"), household, "Leo", "avatar-2", YearMonth(2017, 6), KidUiTier.SPROUT)
    val zoe = Kid(KidId("kid-zoe"), household, "Zoe", "avatar-3", YearMonth(2013, 9), KidUiTier.SPROUT)

    fun kids(): KidsSettingsUiState = KidsSettingsUiState(
        isLoading = false,
        kids = listOf(
            KidRowUi(mia, 6, KidUiTier.SEEDLING),
            KidRowUi(leo, 9, KidUiTier.SPROUT),
            KidRowUi(zoe, 13, KidUiTier.SAPLING),
        ),
    )

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
    ): ParentSecurityUiState = ParentSecurityUiState(
        availability = availability,
        policy = SignaturePolicyUi(hasParentKey = hasParentKey, requireParentSignature = hasParentKey),
        keyNeedsRenewal = keyNeedsRenewal,
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
