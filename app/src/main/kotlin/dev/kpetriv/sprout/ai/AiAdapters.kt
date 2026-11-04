package dev.kpetriv.sprout.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kpetriv.sprout.core.ai.AiAvailability
import dev.kpetriv.sprout.core.ai.AiContext
import dev.kpetriv.sprout.core.ai.AiGateway
import dev.kpetriv.sprout.core.ai.AiPath
import dev.kpetriv.sprout.core.ai.AiPreferences
import dev.kpetriv.sprout.core.ai.KindnessVerdict
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.SessionStore
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.usecase.WeeklyFactsUseCase
import dev.kpetriv.sprout.core.domainhousehold.WeeklyFacts
import dev.kpetriv.sprout.core.domainhousehold.previous
import dev.kpetriv.sprout.core.model.AgeBand
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.core.ui.PickedImageReader
import dev.kpetriv.sprout.feature.chat.FilterVerdict
import dev.kpetriv.sprout.feature.chat.OutgoingMessageFilter
import dev.kpetriv.sprout.feature.chores.ChoreSuggestionUi
import dev.kpetriv.sprout.feature.chores.ChoreSuggestions
import dev.kpetriv.sprout.feature.parenthome.SummaryPath
import dev.kpetriv.sprout.feature.parenthome.SummaryWeek
import dev.kpetriv.sprout.feature.parenthome.WeeklySummarySource
import dev.kpetriv.sprout.feature.parenthome.WeeklySummaryUi
import dev.kpetriv.sprout.feature.settings.AiModelStatus
import dev.kpetriv.sprout.feature.settings.AiSettingsSource
import dev.kpetriv.sprout.feature.wallet.AdjustmentPrefill
import dev.kpetriv.sprout.feature.wallet.ReceiptScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import dev.kpetriv.sprout.feature.settings.AiSettings as AiSettingsUi

/*
 * The adapters between the M6 feature ports and `core/ai`'s AiGateway (the AiRouter: Gemini Nano where available,
 * the rules everywhere). None of them logs or keeps text, image URIs, merchants or kid names (D-M6-7); the router logs
 * only task, path, latency bucket and failure kind.
 */

/**
 * [AiContext] from the stored session (D-M6-34): a KID session exists only on a kid's own linked phone (`AppMode.Kid`
 * with `linkedDevice`), where there is no parent area to change the AI switch, so the router keeps that phone on the
 * rules. Kid mode on a parent's phone keeps the parent's session (PARENT) and follows the parent's switch. Read on
 * every call (one Room row); the router bounds it with the call's budget and takes the rules path if it fails.
 */
class SessionAiContext @Inject constructor(private val sessions: SessionStore) : AiContext {
    override suspend fun isLinkedKidDevice(): Boolean = sessions.get()?.role == Role.KID
}

/**
 * Chat's kindness check ([OutgoingMessageFilter]) over [AiGateway.kindness], tier passed through. The suggestion is
 * only the sendable message (D-M6-13): the card shows it under its own lead-in and "Use this" sends it verbatim
 * (D-M6-2). The 2.5 s fail-open lives in the feature.
 */
class GatewayOutgoingMessageFilter @Inject constructor(private val gateway: AiGateway) : OutgoingMessageFilter {
    override suspend fun check(text: String, tier: KidUiTier): FilterVerdict =
        when (val verdict = gateway.kindness(text, tier)) {
            KindnessVerdict.Ok -> FilterVerdict.Ok
            is KindnessVerdict.Nudge -> FilterVerdict.Nudge(verdict.suggestion)
        }
}

/**
 * The chore editor's "Suggest chores" ([ChoreSuggestions]) over [AiGateway.suggestChores], in the household's fiat
 * currency. The feature passes the kid's band and existing titles (D-M6-23); a blank description becomes null.
 */
class GatewayChoreSuggestions @Inject constructor(
    private val gateway: AiGateway,
    private val currency: FiatCurrencyProvider,
) : ChoreSuggestions {
    override suspend fun suggest(kidId: KidId, band: AgeBand, existing: List<String>): List<ChoreSuggestionUi> =
        gateway.suggestChores(band, existing, currency.fiatCurrency()).map { idea ->
            ChoreSuggestionUi(
                title = idea.title,
                description = idea.description.takeIf { it.isNotBlank() },
                money = idea.money,
                minutes = idea.minutes,
                schedule = idea.schedule.toChoreSchedule(),
            )
        }
}

/** Fires when anything the weekly facts read may have changed, and once when collected. */
fun interface HouseholdActivity {
    fun changes(): Flow<Unit>
}

/**
 * [HouseholdActivity] over Room's invalidation tracker: the ledger, submissions, kids and anomalies tables. A sync
 * stamp write also fires it; [GatewayWeeklySummarySource] drops recomputed facts that did not change.
 */
class RoomHouseholdActivity @Inject constructor(private val database: SproutDatabase) : HouseholdActivity {
    override fun changes(): Flow<Unit> =
        database.invalidationTracker.createFlow("ledger_events", "chore_submissions", "kids", "anomalies").map { }
}

/**
 * The dashboard's weekly summary card ([WeeklySummarySource]): [WeeklyFactsUseCase] for the picked week, then
 * [AiGateway.weeklySummaryResult] (D-M6-3). Recomputed when collected (the dashboard is shown) and again whenever the
 * facts change while it is ([HouseholdActivity]); nothing is stored (D-M6-7). A facts failure is
 * [WeeklySummaryUi.Unavailable] until the next change. [WeeklySummaryUi.Ready.offerModelDownload] is "the model can be
 * downloaded and the parent has not agreed yet" (D-M6-22). The feature's ViewModel emits Loading first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayWeeklySummarySource @Inject constructor(
    private val facts: WeeklyFactsUseCase,
    private val gateway: AiGateway,
    private val preferences: AiPreferences,
    private val activity: HouseholdActivity,
    private val clock: Clock,
    @param:Dispatcher(SproutDispatchers.Default) private val dispatcher: CoroutineDispatcher,
) : WeeklySummarySource {
    override fun observe(week: SummaryWeek): Flow<WeeklySummaryUi> = activity.changes()
        .conflate()
        .map { factsOf(week) }
        .distinctUntilChanged()
        .mapLatest { read -> read?.let { summaryOf(it, week) } ?: WeeklySummaryUi.Unavailable }
        .flowOn(dispatcher)

    /** The facts of [week], or null when they could not be read. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Any failure shows "unavailable"; nothing is logged.
    private suspend fun factsOf(week: SummaryWeek): WeeklyFacts? = try {
        val current = facts.currentWeek()
        facts(if (week == SummaryWeek.THIS_WEEK) current else current.previous())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        null
    }

    private suspend fun summaryOf(read: WeeklyFacts, week: SummaryWeek): WeeklySummaryUi {
        val summary = gateway.weeklySummaryResult(read)
        return WeeklySummaryUi.Ready(
            text = summary.text,
            week = week,
            path = if (summary.path == AiPath.NANO) SummaryPath.NANO else SummaryPath.RULES,
            generatedAt = clock.now(),
            offerModelDownload = offerModelDownload(),
        )
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // No offer when the status or settings fail.
    private suspend fun offerModelDownload(): Boolean = try {
        gateway.status().availability == AiAvailability.DOWNLOADABLE && !preferences.current().downloadConsent
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        false
    }
}

/**
 * The manual adjustment sheet's "Scan a receipt" ([ReceiptScanner], D-M6-21): the picked image read through `core/ui`'s
 * [PickedImageReader] ([PickedImageReader.Limits.RECEIPT], EXIF rotation applied, EXIF dropped, sources over 250 MP
 * refused before decoding), then [AiGateway.receiptPrefill] in the household's fiat currency (on-device text
 * recognition plus rules, never the model; the image and its text stay on the phone). Null when the image cannot be
 * read or nothing was found; the parent reviews and signs whatever is prefilled (D-M6-5).
 *
 * Offered only while the On-device AI switch is on ([scanAllowed]): the recognizer is Google Play services code that
 * records one usage event per scan, so the switch is how a parent turns it off (D-M6-51). The router checks the
 * switch again.
 */
class GatewayReceiptScanner internal constructor(
    private val readImage: suspend (uri: String) -> ByteArray?,
    private val gateway: AiGateway,
    private val scanAllowed: suspend () -> Boolean,
    private val currency: FiatCurrencyProvider,
) : ReceiptScanner {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @Dispatcher(SproutDispatchers.IO) io: CoroutineDispatcher,
        gateway: AiGateway,
        preferences: AiPreferences,
        currency: FiatCurrencyProvider,
    ) : this(
        readImage = { uri ->
            withContext(io) {
                PickedImageReader(context.contentResolver).read(uri, PickedImageReader.Limits.RECEIPT).getOrNull()
            }
        },
        gateway = gateway,
        scanAllowed = { preferences.current().enabled },
        currency = currency,
    )

    override suspend fun isAvailable(): Boolean = scanAllowed()

    override suspend fun scan(imageUri: String): AdjustmentPrefill? = if (!scanAllowed()) {
        null
    } else {
        readImage(imageUri)
            ?.let { jpeg -> gateway.receiptPrefill(jpeg, currency.fiatCurrency()) }
            ?.let { prefill -> AdjustmentPrefill(amount = prefill.amount, merchant = prefill.merchant) }
    }
}

/**
 * Settings → On-device AI ([AiSettingsSource]) over [AiGateway.status] and [AiPreferences] (D-M6-6, D-M6-26). The
 * status is read again whenever the preferences change and after a download request, and every [pollInterval] while a
 * download is under way or was asked for. Consent stores the flag, then asks AICore ([AiGateway.requestDownload]; the
 * router refuses it without the flag); withdrawing it only clears the flag.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class GatewayAiSettingsSource internal constructor(
    private val gateway: AiGateway,
    private val preferences: AiPreferences,
    private val pollInterval: Duration,
) : AiSettingsSource {
    @Inject
    constructor(gateway: AiGateway, preferences: AiPreferences) : this(gateway, preferences, DOWNLOAD_POLL)

    private val refresh = MutableStateFlow(0)

    override fun observe(): Flow<AiSettingsUi> = combine(preferences.settings, refresh) { settings, _ -> settings }
        .transformLatest { settings ->
            while (true) {
                val status = gateway.status().availability.toModel()
                emit(AiSettingsUi(status, settings.enabled, settings.downloadConsent))
                val waiting = status == AiModelStatus.DOWNLOADING ||
                    (status == AiModelStatus.DOWNLOADABLE && settings.downloadConsent)
                if (!waiting) break
                delay(pollInterval)
            }
        }
        .distinctUntilChanged()

    override suspend fun setEnabled(enabled: Boolean) = preferences.setEnabled(enabled)

    override suspend fun setDownloadConsent(consent: Boolean) {
        preferences.setDownloadConsent(consent)
        if (consent) {
            gateway.requestDownload()
            refresh.update { it + 1 }
        }
    }

    private companion object {
        val DOWNLOAD_POLL: Duration = 30.seconds
    }
}

internal fun AiAvailability.toModel(): AiModelStatus = when (this) {
    AiAvailability.AVAILABLE -> AiModelStatus.AVAILABLE
    AiAvailability.DOWNLOADABLE -> AiModelStatus.DOWNLOADABLE
    AiAvailability.DOWNLOADING -> AiModelStatus.DOWNLOADING
    AiAvailability.UNAVAILABLE -> AiModelStatus.UNAVAILABLE
}
