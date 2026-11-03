package dev.kpetriv.sprout.feature.wallet.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.database.usecase.PurchaseRewardUseCase
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.core.ui.MoneyLabels
import dev.kpetriv.sprout.feature.wallet.AdjustmentPromptText
import dev.kpetriv.sprout.feature.wallet.R
import dev.kpetriv.sprout.feature.wallet.RewardPurchaser

/**
 * Wallet bindings that need nothing from the app. [dev.kpetriv.sprout.feature.wallet.WalletDataSource] is bound by the
 * app, `core/domain-ledger`'s `ParentAuthorizationRequester` by `feature/lock`; the repositories, use cases,
 * `LedgerRepository` and `LedgerWriter` by `core/database`.
 */
@Module
@InstallIn(ViewModelComponent::class)
object WalletModule {
    /** A formatter for the locale in effect when the ViewModel is created, with localized unit words. */
    @Provides
    fun moneyFormatter(@ApplicationContext context: Context): MoneyFormatter {
        val resources = context.resources
        return MoneyFormatter(resources.configuration.locales[0], MoneyLabels.fromResources(resources))
    }

    @Provides
    fun rewardPurchaser(useCase: PurchaseRewardUseCase): RewardPurchaser = RewardPurchaser(useCase::invoke)

    @Provides
    fun adjustmentPromptText(@ApplicationContext context: Context): AdjustmentPromptText =
        AdjustmentPromptText { kidName, signedAmount ->
            context.getString(R.string.feature_wallet_adjust_prompt, kidName, signedAmount)
        }
}
