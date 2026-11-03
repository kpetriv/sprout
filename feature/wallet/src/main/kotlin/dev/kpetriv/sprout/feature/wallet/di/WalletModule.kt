package dev.kpetriv.sprout.feature.wallet.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.ui.MoneyFormatter
import dev.kpetriv.sprout.core.ui.MoneyLabels

/**
 * Wallet bindings that need nothing from the app. [dev.kpetriv.sprout.feature.wallet.WalletDataSource],
 * `LedgerRepository` and `LedgerWriter` are bound by the app / `core/database`.
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
}
