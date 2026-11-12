package dev.kpetriv.sprout.feature.parenthome

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId

/**
 * Where the dashboard's links go; the app wires each to its navigation. Chores and Settings are not here: the shell's
 * bottom bar carries them (M9 §5.2).
 */
data class ParentHomeLinks(
    /** The attention card's approvals "Review". */
    val onOpenApprovals: () -> Unit = {},
    val onOpenShop: () -> Unit = {},
    val onSwitchToKidMode: () -> Unit = {},
    /** The kid's wallet in parent view, where the ledger anomalies are listed. */
    val onOpenKid: (KidId) -> Unit = {},
    /** The trial household's "Set up sync": onboarding. */
    val onSetUpSync: () -> Unit = {},
    /** "New device waiting": the approval sheet for that phone (Settings → Devices). */
    val onReviewDevice: (DeviceId) -> Unit = {},
    /** "Signing key not registered with the household": Settings → Security, where "Retry" renews the key. */
    val onRegisterSigningKey: () -> Unit = {},
    /** The summary card's "Download the on-device model": Settings → On-device AI, where the consent row is (M6). */
    val onOpenAiSettings: () -> Unit = {},
)
