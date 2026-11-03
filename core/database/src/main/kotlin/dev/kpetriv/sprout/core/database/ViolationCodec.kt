package dev.kpetriv.sprout.core.database

import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.CurrencySerializer
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.domainledger.PeriodClaim
import dev.kpetriv.sprout.core.domainledger.Violation
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Lossless JSON form of a [Violation] for the anomalies cache, so a stored [dev.kpetriv.sprout.core.domainledger
 * .LedgerAnomaly.InvalidEvent] reads back equal to the projected one. `{"kind": <class name>, ...fields}`.
 *
 * Violations hold ids, amounts, dates, field names and enum values only; never free text (the domain keeps the
 * text itself out of them, and quotes an unknown game id only when it is id-shaped).
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per Violation type, in both directions.
internal object ViolationCodec {
    private const val KIND = "kind"

    fun encode(violation: Violation): JsonObject = buildJsonObject {
        put(KIND, violation::class.simpleName.orEmpty())
        when (violation) {
            is Violation.NonPositiveAmount -> money("amount", violation.amount)

            is Violation.AmountTooLarge -> {
                money("amount", violation.amount)
                money("max", violation.max)
            }

            Violation.Overflow, Violation.ZeroAdjustment, Violation.SameAccountTransfer,
            Violation.InvalidInterestTerms, Violation.TimeBankAlreadyRunning, Violation.TimeBankEmpty,
            Violation.TimeBankNotRunning,
            -> Unit

            is Violation.BlankText -> put("field", violation.field)

            is Violation.TextTooLong -> {
                put("field", violation.field)
                put("maxLength", violation.maxLength)
            }

            is Violation.InvalidText -> put("field", violation.field)

            is Violation.TooManyCauses -> {
                put("count", violation.count)
                put("max", violation.max)
            }

            is Violation.InterestMismatch -> {
                money("expected", violation.expected)
                money("actual", violation.actual)
            }

            is Violation.ParentOnly -> {
                put("eventType", violation.eventType)
                put("authorRole", violation.authorRole.name)
            }

            is Violation.UnknownAccount -> put("accountId", violation.accountId.value)

            is Violation.CurrencyMismatch -> {
                put("accountId", violation.accountId.value)
                currency("expected", violation.expected)
                currency("actual", violation.actual)
            }

            is Violation.KidMismatch -> {
                put("accountId", violation.accountId.value)
                put("expected", violation.expected.value)
                put("actual", violation.actual.value)
            }

            is Violation.WrongAccountKind -> {
                put("accountId", violation.accountId.value)
                put("expected", violation.expected.name)
                put("actual", violation.actual.name)
            }

            is Violation.DuplicateEvent -> put("eventId", violation.eventId.value)

            is Violation.LamportTooFarAhead -> {
                put("latest", violation.latest)
                put("actual", violation.actual)
                put("maxJump", violation.maxJump)
            }

            is Violation.InsufficientFunds -> {
                put("accountId", violation.accountId.value)
                money("available", violation.available)
                money("requested", violation.requested)
            }

            is Violation.DailyCapExceeded -> {
                money("cap", violation.cap)
                money("alreadyCredited", violation.alreadyCredited)
                money("requested", violation.requested)
            }

            is Violation.ParentSignatureMissing -> put("eventType", violation.eventType)

            Violation.InvalidPeriodKey -> Unit

            is Violation.DuplicatePeriod -> {
                put("claim", LedgerJson.json.encodeToJsonElement(PeriodClaim.serializer(), violation.claim))
                put("claimedBy", violation.claimedBy.value)
            }

            is Violation.GrantNotFound -> put("grantedBy", violation.grantedBy.value)

            is Violation.GrantRewardMismatch -> put("grantedBy", violation.grantedBy.value)

            is Violation.GrantMinutesMismatch -> {
                put("grantedBy", violation.grantedBy.value)
                money("expected", violation.expected)
                money("actual", violation.actual)
            }

            is Violation.GrantAlreadyCredited -> {
                put("grantedBy", violation.grantedBy.value)
                put("creditedBy", violation.creditedBy.value)
            }

            // Only an id-shaped game id is kept by the domain; null is stored as an absent key.
            is Violation.UnknownGame -> violation.gameId?.let { put("gameId", it) }

            is Violation.ScoreOutOfRange -> {
                put("score", violation.score)
                put("max", violation.max)
            }

            is Violation.GameCreditTooHigh -> {
                money("earned", violation.earned)
                money("requested", violation.requested)
            }

            is Violation.GameCurrencyNotAllowed -> currency("currency", violation.currency)

            is Violation.PlayedOnOutOfRange -> {
                put("playedOn", violation.playedOn.toString())
                put("today", violation.today.toString())
            }
        }
    }

    /** The violation [json] describes. Throws [IllegalArgumentException] for anything it cannot read. */
    fun decode(json: JsonObject): Violation {
        fun text(key: String) = json.getValue(key).jsonPrimitive.content
        fun money(key: String) = Money(json.getValue(key).jsonPrimitive.long)
        fun currency(key: String) = LedgerJson.json.decodeFromJsonElement(CurrencySerializer, json.getValue(key))
        fun account() = AccountId(text("accountId"))
        fun event(key: String) = EventId(text(key))
        return when (val kind = text(KIND)) {
            "NonPositiveAmount" -> Violation.NonPositiveAmount(money("amount"))

            "AmountTooLarge" -> Violation.AmountTooLarge(money("amount"), money("max"))

            "Overflow" -> Violation.Overflow

            "ZeroAdjustment" -> Violation.ZeroAdjustment

            "BlankText" -> Violation.BlankText(text("field"))

            "TextTooLong" -> Violation.TextTooLong(text("field"), json.getValue("maxLength").jsonPrimitive.int)

            "InvalidText" -> Violation.InvalidText(text("field"))

            "TooManyCauses" -> Violation.TooManyCauses(
                json.getValue("count").jsonPrimitive.int,
                json.getValue("max").jsonPrimitive.int,
            )

            "SameAccountTransfer" -> Violation.SameAccountTransfer

            "InvalidInterestTerms" -> Violation.InvalidInterestTerms

            "InterestMismatch" -> Violation.InterestMismatch(money("expected"), money("actual"))

            "ParentOnly" -> Violation.ParentOnly(text("eventType"), Role.valueOf(text("authorRole")))

            "UnknownAccount" -> Violation.UnknownAccount(account())

            "CurrencyMismatch" -> Violation.CurrencyMismatch(account(), currency("expected"), currency("actual"))

            "KidMismatch" -> Violation.KidMismatch(account(), KidId(text("expected")), KidId(text("actual")))

            "WrongAccountKind" ->
                Violation.WrongAccountKind(
                    account(),
                    AccountKind.valueOf(text("expected")),
                    AccountKind.valueOf(text("actual")),
                )

            "DuplicateEvent" -> Violation.DuplicateEvent(EventId(text("eventId")))

            "LamportTooFarAhead" -> Violation.LamportTooFarAhead(
                latest = json.getValue("latest").jsonPrimitive.long,
                actual = json.getValue("actual").jsonPrimitive.long,
                maxJump = json.getValue("maxJump").jsonPrimitive.long,
            )

            "InsufficientFunds" -> Violation.InsufficientFunds(account(), money("available"), money("requested"))

            "DailyCapExceeded" -> Violation.DailyCapExceeded(money("cap"), money("alreadyCredited"), money("requested"))

            "TimeBankAlreadyRunning" -> Violation.TimeBankAlreadyRunning

            "TimeBankEmpty" -> Violation.TimeBankEmpty

            "TimeBankNotRunning" -> Violation.TimeBankNotRunning

            "ParentSignatureMissing" -> Violation.ParentSignatureMissing(text("eventType"))

            "InvalidPeriodKey" -> Violation.InvalidPeriodKey

            "DuplicatePeriod" -> Violation.DuplicatePeriod(
                claim = LedgerJson.json.decodeFromJsonElement(PeriodClaim.serializer(), json.getValue("claim")),
                claimedBy = event("claimedBy"),
            )

            "GrantNotFound" -> Violation.GrantNotFound(event("grantedBy"))

            "GrantRewardMismatch" -> Violation.GrantRewardMismatch(event("grantedBy"))

            "GrantMinutesMismatch" ->
                Violation.GrantMinutesMismatch(event("grantedBy"), money("expected"), money("actual"))

            "GrantAlreadyCredited" -> Violation.GrantAlreadyCredited(event("grantedBy"), event("creditedBy"))

            "UnknownGame" -> Violation.UnknownGame(json["gameId"]?.jsonPrimitive?.content)

            "ScoreOutOfRange" -> Violation.ScoreOutOfRange(
                json.getValue("score").jsonPrimitive.long,
                json.getValue("max").jsonPrimitive.int,
            )

            "GameCreditTooHigh" -> Violation.GameCreditTooHigh(money("earned"), money("requested"))

            "GameCurrencyNotAllowed" -> Violation.GameCurrencyNotAllowed(currency("currency"))

            "PlayedOnOutOfRange" -> Violation.PlayedOnOutOfRange(
                CalendarDate.parse(text("playedOn")),
                CalendarDate.parse(text("today")),
            )

            else -> throw IllegalArgumentException("Unknown violation kind $kind")
        }
    }

    private fun JsonObjectBuilder.money(key: String, value: Money) = put(key, value.minorUnits)

    private fun JsonObjectBuilder.currency(key: String, value: Currency) =
        put(key, LedgerJson.json.encodeToJsonElement(CurrencySerializer, value))
}
