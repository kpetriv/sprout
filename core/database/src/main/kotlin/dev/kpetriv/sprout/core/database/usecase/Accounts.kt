package dev.kpetriv.sprout.core.database.usecase

import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.AppendResult
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.Violation

/** The MAIN account in [currency], if the kid has one. */
internal fun List<Account>.main(currency: Currency): Account? =
    firstOrNull { it.kind == AccountKind.MAIN && it.currency == currency }

/** The MAIN account in a fiat currency (the kid's money account), if any. */
internal fun List<Account>.fiatMain(): Account? = firstOrNull {
    it.kind == AccountKind.MAIN &&
        it.currency is Currency.Fiat
}

/** The event that already holds the period this rejection was about, if it was rejected only for that. */
internal fun AppendResult.duplicatePeriodHolder(): EventId? =
    ((this as? AppendResult.Rejected)?.violations?.singleOrNull() as? Violation.DuplicatePeriod)?.claimedBy

/** [AppendResult.Rejected.reason] when the kid has no account to post the event to. */
const val NO_ACCOUNT_REJECTION: String = "no matching account"
