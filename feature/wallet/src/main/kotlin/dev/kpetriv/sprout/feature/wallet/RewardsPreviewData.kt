package dev.kpetriv.sprout.feature.wallet

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.core.model.RewardKind

/** Fixed rewards list and editor states for previews, screen tests and screenshot goldens. */
internal object RewardsPreviewData {
    private val gbp = Currency.Fiat("GBP")
    val mia = RewardKidOption(KidId("kid-mia"), "Mia")
    val leo = RewardKidOption(KidId("kid-leo"), "Leo")
    val zoe = RewardKidOption(KidId("kid-zoe"), "Zoe")

    /** Four rewards: two for every kid, one only for Mia and Zoe, and a hidden one. */
    val rows: List<RewardRowUi> = listOf(
        row("reward-tablet", "30 minutes of screen time", RewardKind.SCREEN_TIME, 150, minutes = 30),
        row("reward-ice-cream", "Ice cream", RewardKind.TREAT, 250),
        row(
            "reward-zoo",
            "Trip to the zoo",
            RewardKind.OUTING,
            1_500,
            forKids = RewardAudience.Kids(listOf(mia.name, zoe.name)),
        ),
        row("reward-stickers", "Sticker pack", RewardKind.OTHER, 100, active = false),
    )

    fun list(): RewardListUiState.Ready = RewardListUiState.Ready(rows)

    /** A new reward, nothing typed yet. */
    fun editorNew(): RewardEditorUiState =
        RewardEditorUiState(isLoading = false, isNew = true, kids = listOf(mia, leo, zoe), currency = gbp)

    /** Editing a screen-time reward offered to Mia and Leo. */
    fun editorScreenTime(): RewardEditorUiState = editorNew().copy(
        isNew = false,
        title = "30 minutes of screen time",
        kind = RewardKind.SCREEN_TIME,
        priceText = "1.50",
        minutesText = "30",
        forAllKids = false,
        chosenKids = setOf(mia.id, leo.id),
    )

    /** A save attempt with a blank name, a zero price, screen time without minutes and no kid chosen. */
    fun editorErrors(): RewardEditorUiState = editorNew().copy(
        kind = RewardKind.SCREEN_TIME,
        priceText = "0",
        forAllKids = false,
        errors = setOf(
            RewardEditorError.TITLE_BLANK,
            RewardEditorError.PRICE_NOT_POSITIVE,
            RewardEditorError.MINUTES_MISSING,
            RewardEditorError.NO_KIDS_CHOSEN,
        ),
    )

    @Suppress("LongParameterList") // A row's fields, with defaults for the usual case.
    private fun row(
        id: String,
        title: String,
        kind: RewardKind,
        cost: Long,
        minutes: Int? = null,
        forKids: RewardAudience = RewardAudience.AllKids,
        active: Boolean = true,
    ) = RewardRowUi(RewardId(id), title, kind, Money(cost), gbp, minutes, forKids, active)
}

@Preview(name = "Rewards", showBackground = true, heightDp = 900)
@Composable
private fun RewardListPreview() = SproutTheme { RewardListScreen(state = RewardsPreviewData.list(), onEvent = {}) }

@Preview(name = "Rewards, empty", showBackground = true)
@Composable
private fun RewardListEmptyPreview() =
    SproutTheme { RewardListScreen(state = RewardListUiState.Ready(emptyList()), onEvent = {}) }

@Preview(name = "Reward editor, screen time", showBackground = true, heightDp = 900)
@Composable
private fun RewardEditorPreview() =
    SproutTheme { RewardEditorScreen(state = RewardsPreviewData.editorScreenTime(), onEvent = {}) }
