package dev.kpetriv.sprout.feature.parenthome

import dev.kpetriv.sprout.core.database.FlaggedPurchase
import dev.kpetriv.sprout.core.domainledger.EventId
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.Kid

/**
 * One row of the "Purchases to check" card (D-REL-65): a kid's purchase, pulled from their phone, that matches no
 * version of its reward as a parent set it up (price, screen time, who can buy it) as far as this phone has seen. A
 * modified kid phone can cause it (paying less, claiming more minutes, buying a hidden or a sibling's reward), and so
 * can a phone that missed a change. [eventId] and [hash]
 * identify the purchase for [ParentHomeEvent.DismissPurchase]; dismissing only hides the row, the ledger is unchanged.
 *
 * @property kidName the kid's first name; null when the kid is not in the household on this phone.
 * @property rewardTitle the reward's current title; null when this phone has no such reward (deleted, not pulled
 *   yet, or an id the kid's phone made up).
 * @property paid what the purchase took from the kid's balance.
 * @property minutes the screen time the purchase credited; null for none.
 * @property now the reward's current price; null with [rewardTitle].
 */
data class PurchaseToCheckUi(
    val eventId: EventId,
    val hash: String,
    val kidName: String?,
    val rewardTitle: String?,
    val paid: MoneyUi,
    val minutes: Money?,
    val now: RewardPriceUi?,
) {
    override fun toString(): String =
        "PurchaseToCheckUi(eventId=$eventId, kidName=<redacted>, rewardTitle=<redacted>, paid=$paid, " +
            "minutes=$minutes, now=$now)"
}

/** A reward's current price, and the screen time it gives (null for none). */
data class RewardPriceUi(val cost: MoneyUi, val minutes: Money?)

/** The open [flags] as rows, newest flag first, each kid named by first name from [kids] (never by id). */
internal fun purchasesToCheck(flags: List<FlaggedPurchase>, kids: List<Kid>): List<PurchaseToCheckUi> {
    val names = kids.associate { it.id to it.firstName }
    return flags.sortedByDescending { it.flaggedAt }.map { flag ->
        val reward = flag.currentReward
        PurchaseToCheckUi(
            eventId = flag.eventId,
            hash = flag.hash,
            kidName = names[flag.kidId],
            rewardTitle = reward?.title,
            paid = MoneyUi(flag.amount, flag.currency),
            minutes = flag.minutesGranted,
            now = reward?.let { now ->
                RewardPriceUi(MoneyUi(now.cost, now.currency), now.minutesGranted?.toLong()?.let(::Money))
            },
        )
    }
}
