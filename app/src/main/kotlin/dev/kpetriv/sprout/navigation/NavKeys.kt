package dev.kpetriv.sprout.navigation

import androidx.navigation3.runtime.NavKey
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
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

// Kid area (no lock).

/** Kid mode's landing screen: the kid's own wallet. */
@Serializable
data class KidHomeKey(val kidId: KidId) : NavKey

/** The kid's chores for today, in the kid's [tier]. */
@Serializable
data class KidChoresKey(val kidId: KidId, val tier: KidUiTier) : NavKey
