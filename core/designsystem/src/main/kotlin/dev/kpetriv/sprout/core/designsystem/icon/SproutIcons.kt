package dev.kpetriv.sprout.core.designsystem.icon

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.kpetriv.sprout.core.designsystem.R

/**
 * Sprout's control icons (M9 §2.7, D-M9-5): Material Symbols Outlined at weight 500, 24 dp, bundled as vector
 * drawables in this module (`res/drawable/sprout_ic_*.xml`, converted from fonts.gstatic.com's SVGs). Outlined
 * everywhere; the `*Filled` variants are for an active state only (the selected bottom-bar item). Tint them through
 * `Icon`. Directional icons (arrow back, chevron, send, backspace) mirror in right-to-left layouts.
 */
object SproutIcons {
    /** Material Symbols "account_balance_wallet". */
    val AccountBalanceWallet: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_account_balance_wallet)

    /** Material Symbols "account_balance_wallet", filled (active state). */
    val AccountBalanceWalletFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_account_balance_wallet_filled)

    /** Material Symbols "add". */
    val Add: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_add)

    /** Material Symbols "arrow_back". */
    val ArrowBack: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_arrow_back)

    /** Material Symbols "auto_awesome". */
    val AutoAwesome: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_auto_awesome)

    /** Material Symbols "backspace". */
    val Backspace: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_backspace)

    /** Material Symbols "chat_bubble". */
    val ChatBubble: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_chat_bubble)

    /** Material Symbols "chat_bubble", filled (active state). */
    val ChatBubbleFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_chat_bubble_filled)

    /** Material Symbols "check". */
    val Check: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_check)

    /** Material Symbols "check_circle". */
    val CheckCircle: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_check_circle)

    /** Material Symbols "checklist". */
    val Checklist: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_checklist)

    /** Material Symbols "checklist", filled (active state). */
    val ChecklistFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_checklist_filled)

    /** Material Symbols "chevron_right". */
    val ChevronRight: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_chevron_right)

    /** Material Symbols "close". */
    val Close: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_close)

    /** Material Symbols "delete". */
    val Delete: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_delete)

    /** Material Symbols "eco". */
    val Eco: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_eco)

    /** Material Symbols "edit". */
    val Edit: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_edit)

    /** Material Symbols "emoji_events". */
    val EmojiEvents: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_emoji_events)

    /** Material Symbols "home". */
    val Home: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_home)

    /** Material Symbols "home", filled (active state). */
    val HomeFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_home_filled)

    /** Material Symbols "lock". */
    val Lock: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_lock)

    /** Material Symbols "mic". */
    val Mic: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_mic)

    /** Material Symbols "more_vert". */
    val MoreVert: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_more_vert)

    /** Material Symbols "payments". */
    val Payments: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_payments)

    /** Material Symbols "redeem". */
    val Redeem: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_redeem)

    /** Material Symbols "savings". */
    val Savings: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_savings)

    /** Material Symbols "schedule". */
    val Schedule: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_schedule)

    /** Material Symbols "send". */
    val Send: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_send)

    /** Material Symbols "settings". */
    val Settings: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_settings)

    /** Material Symbols "settings", filled (active state). */
    val SettingsFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_settings_filled)

    /** Material Symbols "shopping_bag". */
    val ShoppingBag: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_shopping_bag)

    /** Material Symbols "sports_esports". */
    val SportsEsports: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_sports_esports)

    /** Material Symbols "sports_esports", filled (active state). */
    val SportsEsportsFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_sports_esports_filled)

    /** Material Symbols "star". */
    val Star: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_star)

    /** Material Symbols "star", filled (active state). */
    val StarFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_star_filled)

    /** Material Symbols "storefront". */
    val Storefront: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_storefront)

    /** Material Symbols "storefront", filled (active state). */
    val StorefrontFilled: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_storefront_filled)

    /** Material Symbols "timer". */
    val Timer: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_timer)

    /** Material Symbols "volume_up". */
    val VolumeUp: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_volume_up)

    /** Material Symbols "wb_sunny". */
    val WbSunny: ImageVector
        @Composable get() = ImageVector.vectorResource(R.drawable.sprout_ic_wb_sunny)
}
