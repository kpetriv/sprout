package dev.kpetriv.sprout.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.ai.GatewayAiSettingsSource
import dev.kpetriv.sprout.ai.GatewayChoreSuggestions
import dev.kpetriv.sprout.ai.GatewayOutgoingMessageFilter
import dev.kpetriv.sprout.ai.GatewayReceiptScanner
import dev.kpetriv.sprout.ai.GatewayWeeklySummarySource
import dev.kpetriv.sprout.ai.HouseholdActivity
import dev.kpetriv.sprout.ai.RoomHouseholdActivity
import dev.kpetriv.sprout.feature.chat.OutgoingMessageFilter
import dev.kpetriv.sprout.feature.chores.ChoreSuggestions
import dev.kpetriv.sprout.feature.parenthome.WeeklySummarySource
import dev.kpetriv.sprout.feature.settings.AiSettingsSource
import dev.kpetriv.sprout.feature.wallet.ReceiptScanner

/**
 * The on-device AI ports of the features (M6 §3) bound to `core/ai`'s `AiGateway` (`core/ai`'s own `AiModule` binds
 * it to the `AiRouter` and provides `AiPreferences`).
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class AiAdaptersModule {
    @Binds
    abstract fun outgoingMessageFilter(impl: GatewayOutgoingMessageFilter): OutgoingMessageFilter

    @Binds
    abstract fun choreSuggestions(impl: GatewayChoreSuggestions): ChoreSuggestions

    @Binds
    abstract fun weeklySummarySource(impl: GatewayWeeklySummarySource): WeeklySummarySource

    @Binds
    abstract fun householdActivity(impl: RoomHouseholdActivity): HouseholdActivity

    @Binds
    abstract fun receiptScanner(impl: GatewayReceiptScanner): ReceiptScanner

    @Binds
    abstract fun aiSettingsSource(impl: GatewayAiSettingsSource): AiSettingsSource
}
