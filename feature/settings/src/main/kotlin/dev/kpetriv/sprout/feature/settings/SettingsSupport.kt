package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.database.FiatCurrencyProvider
import dev.kpetriv.sprout.core.database.HouseholdPolicyRepository
import dev.kpetriv.sprout.core.database.LocalTrial
import dev.kpetriv.sprout.core.database.NoHouseholdPolicyYetException
import dev.kpetriv.sprout.core.database.SessionAuthorProvider
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.ui.AmountInputError
import dev.kpetriv.sprout.core.ui.MinorUnitsParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** The household settings write into: its id (from the session author) and its money currency. */
class HouseholdContext @Inject constructor(
    private val authors: SessionAuthorProvider,
    private val policies: HouseholdPolicyRepository,
    private val currencies: FiatCurrencyProvider,
) {
    suspend fun householdId(): HouseholdId = authors.currentAuthor().householdId

    /**
     * The household policy's currency (the locale's until the policy exists) now and whenever the policy changes (a
     * pull brings it): for what a screen shows or offers.
     */
    fun observeFiatCurrency(): Flow<Currency.Fiat> =
        policies.observe().map { it?.fiatCurrency ?: currencies.currentFiatCurrency() }.distinctUntilChanged()

    /**
     * The currency a persistent write is made in (a new kid's accounts): the policy's. The trial household without a
     * policy row yet takes the locale's; a real household without one refuses with [NoHouseholdPolicyYetException], so
     * nothing is written in a currency the household did not choose (D-REL-18).
     */
    suspend fun policyCurrency(): Currency.Fiat {
        policies.get()?.let { return it.fiatCurrency }
        if (householdId() != LocalTrial.HOUSEHOLD) throw NoHouseholdPolicyYetException()
        return currencies.currentFiatCurrency()
    }
}

/** Why typed amount text was refused. */
enum class AmountError {
    REQUIRED,
    NOT_A_NUMBER,
    TOO_MANY_DECIMALS,
    TOO_LARGE,
    MUST_BE_POSITIVE,
}

/** Parses a positive amount of [currency] into minor units, or the error to show. Never floating point. */
internal fun parsePositiveAmount(text: String, currency: Currency.Fiat, max: Long): Pair<Long?, AmountError?> =
    when (val parsed = MinorUnitsParser.parse(text, currency.fractionDigits, max)) {
        MinorUnitsParser.Result.Empty -> null to AmountError.REQUIRED

        is MinorUnitsParser.Result.Invalid -> null to parsed.error.toAmountError()

        is MinorUnitsParser.Result.Valid ->
            if (parsed.minorUnits > 0) parsed.minorUnits to null else null to AmountError.MUST_BE_POSITIVE
    }

internal fun AmountInputError.toAmountError(): AmountError = when (this) {
    AmountInputError.NOT_A_NUMBER -> AmountError.NOT_A_NUMBER
    AmountInputError.TOO_MANY_DECIMALS -> AmountError.TOO_MANY_DECIMALS
    AmountInputError.TOO_LARGE -> AmountError.TOO_LARGE
}

/** Runs [block]; a storage failure becomes false instead of crashing (cancellation still propagates). */
@Suppress("TooGenericExceptionCaught") // Any repository failure must surface as a message, not crash settings.
internal suspend fun attempt(block: suspend () -> Unit): Boolean = try {
    block()
    true
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

/** How a household policy write went (D-M7-57). */
internal enum class PolicyWrite { SAVED, NO_POLICY_YET, FAILED }

/**
 * Runs a [HouseholdRules] write: [PolicyWrite.NO_POLICY_YET] when this phone has no household policy yet
 * (`NoHouseholdPolicyYetException`), [PolicyWrite.FAILED] for any other failure.
 */
@Suppress("TooGenericExceptionCaught") // Any failure becomes a message.
internal suspend fun policyWrite(block: suspend () -> Unit): PolicyWrite = try {
    block()
    PolicyWrite.SAVED
} catch (e: CancellationException) {
    throw e
} catch (_: NoHouseholdPolicyYetException) {
    PolicyWrite.NO_POLICY_YET
} catch (_: Exception) {
    PolicyWrite.FAILED
}

/** One-off snackbar messages shared by the settings sections. */
enum class SettingsMessage {
    SAVED,
    KID_ADDED,
    KID_ARCHIVED,
    GOAL_DELETED,
    PIN_CHANGED,
    KEY_REGISTERED,

    /** The key works on this device, but the household's server did not take it (offline): retry later. */
    KEY_NOT_SHARED,
    SAVE_FAILED,

    /** The parent's fingerprint or PIN check failed: nothing changed. */
    NOT_CONFIRMED,

    /** This phone has no household policy yet, so it does not write one (D-M7-57). */
    POLICY_NOT_SYNCED,

    /** The household policy that existed nowhere was restored from this phone (D-REL-22). */
    SETTINGS_RESTORED,

    /** Nothing on this phone could open a web link (About). */
    LINK_NOT_OPENED,
}

/** How long a section's state flow keeps its upstream after the screen stops collecting (rotation survives). */
internal const val STOP_TIMEOUT_MILLIS = 5_000L

/** A section's data while it loads. */
internal sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    data class Ready<T>(val value: T) : Load<T>

    data object Failed : Load<Nothing>
}
