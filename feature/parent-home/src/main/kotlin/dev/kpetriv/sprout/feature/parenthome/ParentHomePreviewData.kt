package dev.kpetriv.sprout.feature.parenthome

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.kpetriv.sprout.core.designsystem.theme.SproutTheme
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.YearMonth
import kotlin.time.Instant

/** Fixed dashboard states for previews and screenshot tests. No clock, no random ids, no real people. */
@Suppress("MagicNumber") // Sample balances, dates and counts are the data itself.
object ParentHomePreviewData {
    private val household = HouseholdId("preview-household")
    private val gbp = Currency.Fiat("GBP")

    val mia = Kid(KidId("kid-mia"), household, "Mia", "avatar-1", YearMonth(2020, 3), KidUiTier.SEEDLING)
    val leo = Kid(KidId("kid-leo"), household, "Leo", "avatar-2", YearMonth(2017, 6), KidUiTier.SPROUT)
    val zoe = Kid(KidId("kid-zoe"), household, "Zoe", "avatar-3", YearMonth(2013, 9), KidUiTier.SAPLING)

    /** A household with no kids yet and nothing waiting. */
    fun empty(): ParentHomeUiState.Ready = ParentHomeUiState.Ready(
        pendingApprovals = 0,
        pendingActions = PendingActionsUi(allowances = 0, interest = 0),
        kids = emptyList(),
    )

    /** Three kids, chores to approve, payments to sign and one ledger anomaly. */
    fun busy(signing: SigningProgress? = null): ParentHomeUiState.Ready = ParentHomeUiState.Ready(
        pendingApprovals = 4,
        pendingActions = PendingActionsUi(allowances = 2, interest = 1),
        kids = listOf(
            KidSummaryUi(mia, MoneyUi(Money(735), gbp), Money(45), null, anomalies = 0),
            KidSummaryUi(leo, MoneyUi(Money(1_250), gbp), Money(80), CalendarDate(2026, 11, 7), anomalies = 1),
            KidSummaryUi(zoe, MoneyUi(Money(4_210), gbp), Money(120), CalendarDate(2026, 12, 1), anomalies = 0),
        ),
        signing = signing,
    )

    /** The trial household on a fresh install: the banner offering "Set up sync". */
    fun trial(): ParentHomeUiState.Ready = empty().copy(household = HouseholdStatus(trial = true))

    /** A synced household with a parent phone waiting for approval and two refused events. */
    fun household(): ParentHomeUiState.Ready = empty().copy(
        household = HouseholdStatus(
            sync = SyncLine(Instant.parse("2026-11-03T09:41:00Z"), blockedCode = null, failing = false),
            rejected = listOf(
                RejectedEvent(
                    "PARENT_SIGNATURE_MISSING",
                    DeviceLabel(Role.KID, "Leo", "c0ff ee00 d00d feed"),
                    Instant.parse("2026-11-03T08:15:00Z"),
                ),
                RejectedEvent(
                    "DEVICE_NOT_ACTIVE",
                    DeviceLabel(Role.KID, "Mia", "dead 0000 beef 1111"),
                    Instant.parse("2026-11-02T19:02:00Z"),
                ),
            ),
            pendingDevices = listOf(PendingDevice(DeviceId("3fa9c01d77e2b4a8".padEnd(64, '0')), "3fa9 c01d 77e2 b4a8")),
        ),
    )

    /** Sync blocked by a refused ledger entry of this phone. */
    fun syncBlocked(): ParentHomeUiState.Ready = empty().copy(
        household = HouseholdStatus(
            sync = SyncLine(Instant.parse("2026-11-03T09:41:00Z"), blockedCode = "permission-denied", failing = false),
        ),
    )

    /** A synced household whose parent signing key never reached the server (D-M3-39). */
    fun signingKeyNotRegistered(): ParentHomeUiState.Ready = empty().copy(
        household = HouseholdStatus(
            sync = SyncLine(Instant.parse("2026-11-03T09:41:00Z"), blockedCode = null, failing = false),
            signingKeyNotRegistered = true,
        ),
    )

    /** A synced household whose parent phone has fingerprint signing off (D-M3-46). */
    fun signingOff(): ParentHomeUiState.Ready = empty().copy(
        household = HouseholdStatus(
            sync = SyncLine(Instant.parse("2026-11-03T09:41:00Z"), blockedCode = null, failing = false),
            signingOff = true,
        ),
    )

    /** Key of the skipped allowance in [needsAttention]. */
    const val SKIPPED_KEY: String = "allowance/leo-weekly/2026-W30"

    /** A quiet household with one failed interest payment and one skipped allowance period. */
    fun needsAttention(): ParentHomeUiState.Ready = empty().copy(
        kids = listOf(
            KidSummaryUi(leo, MoneyUi(Money(1_250), gbp), Money(80), CalendarDate(2026, 11, 7), anomalies = 0),
            KidSummaryUi(zoe, MoneyUi(Money(4_210), gbp), Money(120), CalendarDate(2026, 12, 1), anomalies = 0),
        ),
        attention = listOf(
            AttentionItemUi(
                key = "interest/kid-zoe-savings/2026-10",
                kidName = zoe.firstName,
                kind = PaymentKind.INTEREST,
                amount = MoneyUi(Money(21), gbp),
                period = "2026-10",
                reason = AttentionReason.FAILED,
            ),
            AttentionItemUi(
                key = SKIPPED_KEY,
                kidName = leo.firstName,
                kind = PaymentKind.ALLOWANCE,
                amount = MoneyUi(Money(300), gbp),
                period = "2026-W30",
                reason = AttentionReason.SKIPPED,
            ),
        ),
    )
}

@Preview
@Composable
private fun ParentHomeBusyPreview() {
    SproutTheme {
        ParentHomeScreen(ParentHomePreviewData.busy(), onEvent = {}, onSignNow = {}, links = ParentHomeLinks())
    }
}
