package dev.kpetriv.sprout.core.ai.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.ai.AiCallLog
import dev.kpetriv.sprout.core.ai.AiGateway
import dev.kpetriv.sprout.core.ai.AiPreferences
import dev.kpetriv.sprout.core.ai.AiRouter
import dev.kpetriv.sprout.core.ai.AiTimeouts
import dev.kpetriv.sprout.core.ai.DataStoreAiPreferences
import dev.kpetriv.sprout.core.ai.GeminiNanoAiGateway
import dev.kpetriv.sprout.core.ai.MlKitPromptModel
import dev.kpetriv.sprout.core.ai.MlKitReceiptTextReader
import dev.kpetriv.sprout.core.ai.PromptModel
import dev.kpetriv.sprout.core.ai.ReceiptTextReader
import dev.kpetriv.sprout.core.ai.RulesAiGateway
import dev.kpetriv.sprout.core.ai.TimberAiCallLog
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * On-device AI (M6 §1): [AiGateway] is the [AiRouter] over [GeminiNanoAiGateway] and [RulesAiGateway], with the
 * parent's [AiPreferences] in the `ai_preferences` DataStore file.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {
    @Binds
    abstract fun gateway(router: AiRouter): AiGateway

    companion object {
        @Provides
        @Singleton
        fun preferences(@ApplicationContext context: Context): AiPreferences = DataStoreAiPreferences(
            PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("ai_preferences") },
        )

        @Provides
        @Singleton
        fun receiptReader(@Dispatcher(SproutDispatchers.Default) dispatcher: CoroutineDispatcher): ReceiptTextReader =
            MlKitReceiptTextReader(dispatcher)

        @Provides
        @Singleton
        fun promptModel(@Dispatcher(SproutDispatchers.Default) dispatcher: CoroutineDispatcher): PromptModel =
            MlKitPromptModel(CoroutineScope(SupervisorJob() + dispatcher))

        @Provides
        fun timeouts(): AiTimeouts = AiTimeouts()

        @Provides
        fun callLog(): AiCallLog = TimberAiCallLog

        @Provides
        @Singleton
        fun rules(receiptReader: ReceiptTextReader): RulesAiGateway = RulesAiGateway(receiptReader)

        @Provides
        @Singleton
        fun nano(model: PromptModel, rules: RulesAiGateway, timeouts: AiTimeouts): GeminiNanoAiGateway =
            GeminiNanoAiGateway(model, rules, timeouts)

        @Provides
        @Singleton
        fun router(
            nano: GeminiNanoAiGateway,
            rules: RulesAiGateway,
            preferences: AiPreferences,
            log: AiCallLog,
            timeouts: AiTimeouts,
        ): AiRouter = AiRouter(nano, rules, preferences, log, timeouts)
    }
}
