package dev.kpetriv.sprout.core.model

import kotlinx.serialization.Serializable

/** Who is acting. Parent-only operations (approvals, allowance, adjustments) are gated on [PARENT]. */
@Serializable
enum class Role {
    PARENT,
    KID,
}
