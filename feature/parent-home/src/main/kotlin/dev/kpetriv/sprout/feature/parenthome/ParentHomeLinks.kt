package dev.kpetriv.sprout.feature.parenthome

import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.KidId

/** Where the dashboard's links go; the app wires each to its navigation. */
data class ParentHomeLinks(
    val onOpenChores: () -> Unit = {},
    val onOpenApprovals: () -> Unit = {},
    val onOpenShop: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onSwitchToKidMode: () -> Unit = {},
    /** The kid's wallet in parent view, where the ledger anomalies are listed. */
    val onOpenKid: (KidId) -> Unit = {},
    /** The trial household's "Set up sync": onboarding. */
    val onSetUpSync: () -> Unit = {},
    /** "New device waiting": the approval sheet for that phone (Settings → Devices). */
    val onReviewDevice: (DeviceId) -> Unit = {},
)
