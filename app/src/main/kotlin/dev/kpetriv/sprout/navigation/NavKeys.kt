package dev.kpetriv.sprout.navigation

import androidx.navigation3.runtime.NavKey
import dev.kpetriv.sprout.core.games.GameKind
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.RewardId
import dev.kpetriv.sprout.feature.wallet.WalletMode
import kotlinx.serialization.Serializable

// Parent area (behind RequireParent).

/** The parent dashboard: the parent area's landing screen. */
@Serializable
data object ParentHomeKey : NavKey

/** One kid's wallet. In the parent area [mode] is [WalletMode.PARENT]. */
@Serializable
data class WalletKey(val kidId: KidId, val mode: WalletMode) : NavKey

/** One kid's shop, in either area. */
@Serializable
data class ShopKey(val kidId: KidId, val mode: WalletMode) : NavKey

/** The parent's rewards list: the household's shop catalogue, from the dashboard's "Shop" or "Edit rewards". */
@Serializable
data object RewardsKey : NavKey

/** Add ([rewardId] null) or edit a shop reward. */
@Serializable
data class RewardEditorKey(val rewardId: RewardId? = null) : NavKey

/** The parent's chore list. */
@Serializable
data object ChoresKey : NavKey

/** Create ([choreId] null) or edit a chore. */
@Serializable
data class ChoreEditorKey(val choreId: ChoreId? = null) : NavKey

/** The approvals inbox. */
@Serializable
data object ApprovalsKey : NavKey

/** Parent settings. */
@Serializable
data object SettingsKey : NavKey

/** Settings → Devices, opened from the dashboard's "New device waiting" card on [reviewDeviceId]'s approval sheet. */
@Serializable
data class DevicesKey(val reviewDeviceId: DeviceId? = null) : NavKey

/** Settings → Security, opened from the dashboard's "Signing key not registered with the household" card. */
@Serializable
data object SecurityKey : NavKey

/** Settings → On-device AI, opened from the weekly summary card's "Download the on-device model" (M6). */
@Serializable
data object AiSettingsKey : NavKey

// Kid area (no lock).

/** Kid mode's landing screen: the kid's own wallet. */
@Serializable
data class KidHomeKey(val kidId: KidId) : NavKey

/** The kid's chores for today, in the kid's [tier]. */
@Serializable
data class KidChoresKey(val kidId: KidId, val tier: KidUiTier) : NavKey

// Chat and time bank (M4), in either area.

/** The parent's chat inbox: one thread per kid. */
@Serializable
data object ChatThreadsKey : NavKey

/**
 * [kidId]'s chat thread: a parent's view in the parent area, the kid's own view (in the kid's tier) in kid mode.
 */
@Serializable
data class ChatThreadKey(val kidId: KidId) : NavKey

/** [kidId]'s screen time (time bank), kid mode only. */
@Serializable
data class TimeBankKey(val kidId: KidId) : NavKey

// Brain games (M5), kid mode only.

/** [kidId]'s games hub, in the kid's [tier]. */
@Serializable
data class KidGamesKey(val kidId: KidId, val tier: KidUiTier) : NavKey

/** One play of [kind] by [kidId] in [tier]: start card, rounds and result in one entry. */
@Serializable
data class GamePlayKey(val kidId: KidId, val tier: KidUiTier, val kind: GameKind) : NavKey
