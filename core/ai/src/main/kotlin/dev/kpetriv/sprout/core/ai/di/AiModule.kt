package dev.kpetriv.sprout.core.ai.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.kpetriv.sprout.core.ai.AiCallLog
import dev.kpetriv.sprout.core.ai.AiContext
import dev.kpetriv.sprout.core.ai.AiGateway
import dev.kpetriv.sprout.core.ai.AiPreferences
import dev.kpetriv.sprout.core.ai.AiRouter
import dev.kpetriv.sprout.core.ai.AiTimeouts
import dev.kpetriv.sprout.core.ai.DataStoreAiPreferences
import dev.kpetriv.sprout.core.ai.GeminiNanoAiGateway
import dev.kpetriv.sprout.core.ai.MlKitPromptModel
import dev.kpetriv.sprout.core.ai.MlKitReceiptTextReader
import dev.kpetriv.sprout.core.ai.RulesAiGateway
import dev.kpetriv.sprout.core.ai.TimberAiCallLog
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * On-device AI (M6 §1): [AiGateway] is the router over Gemini Nano and the rules, with the parent's [AiPreferences]
 * in the `ai_preferences` DataStore file. Only [AiGateway] and [AiPreferences] are bound: the Nano gateway, its
 * `PromptModel` and the rules gateway are built here and are not injectable, so no caller can reach the model without
 * the router's switch, kid-device and consent gates (audit L6). The app binds [AiContext] from the session (D-M6-34).
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {
    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): AiPreferences = DataStoreAiPreferences(
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("ai_preferences") },
    )

    @Provides
    fun timeouts(): AiTimeouts = AiTimeouts()

    @Provides
    fun callLog(): AiCallLog = TimberAiCallLog

    @Provides
    @Singleton
    fun gateway(
        @Dispatcher(SproutDispatchers.Default) dispatcher: CoroutineDispatcher,
        preferences: AiPreferences,
        context: AiContext,
        log: AiCallLog,
        timeouts: AiTimeouts,
    ): AiGateway {
        val rules = RulesAiGateway(MlKitReceiptTextReader(dispatcher))
        val model = MlKitPromptModel(CoroutineScope(SupervisorJob() + dispatcher))
        val nano = GeminiNanoAiGateway(model, rules, timeouts)
        return AiRouter(nano, rules, preferences, context, log, timeouts)
    }
}
