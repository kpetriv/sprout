package dev.kpetriv.sprout.navigation

import androidx.navigation3.runtime.NavKey
import dev.kpetriv.sprout.core.model.KidId
import kotlinx.serialization.Serializable

/** Navigation key of one kid's wallet. */
@Serializable
data class WalletKey(val kidId: KidId) : NavKey
