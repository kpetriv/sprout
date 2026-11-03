package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.AccountId
import dev.kpetriv.sprout.core.model.KidId

/** What kind of [dev.kpetriv.sprout.core.domainledger.LedgerAnomaly] a row caches. */
enum class AnomalyKind {
    OVERDRAFT,
    DAILY_CAP_EXCEEDED,
    INVALID_EVENT,
    CONFLICTING_DUPLICATE,

    /** A pulled game dated outside the played-on window of its server stamp (M5, D-M5-5). */
    PLAYED_ON_OUT_OF_RANGE,
}

/**
 * Cache of the anomalies in each kid's projection, for the parent audit view. Replaced as a whole on every
 * projection of the household, so a resolved anomaly disappears. Never the source of truth.
 *
 * @property anomalyId stable across rebuilds: the kind plus the account or event it is about, and the kid for kinds
 *   one event can raise for several kids (see `AnomalyMapper`).
 * @property eventId the event that triggered it (the first cause, for overdrafts).
 * @property amountMinor shortfall (overdraft) or uncredited excess (daily cap); null otherwise.
 * @property detailsJson ids, hashes, roles and violation fields only; never free text or personal data.
 * @property appliedCopyJson for a conflicting duplicate (schema version 3): the copy the projection applied, as JSON,
 *   or the JSON literal `null` when it applied none. A SQL NULL (rows cached before version 3) means unknown.
 */
@Entity(tableName = "anomalies", indices = [Index(value = ["kidId"])])
data class AnomalyEntity(
    @PrimaryKey val anomalyId: String,
    val kidId: KidId,
    val kind: AnomalyKind,
    val accountId: AccountId?,
    val eventId: String,
    val amountMinor: Money?,
    val detailsJson: String,
    val detectedAtEpochMs: Long,
    val appliedCopyJson: String? = null,
)
