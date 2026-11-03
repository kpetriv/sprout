package dev.kpetriv.sprout.core.database.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.InterestPolicy

/** The household's savings interest ([InterestPolicy]); one row per household. A synced document. */
@Entity(tableName = "interest_policy")
data class InterestPolicyEntity(
    @PrimaryKey val householdId: HouseholdId,
    val annualRateBasisPoints: Int,
    val accrualDay: Int,
    val lamport: Long,
    @Embedded val stamp: DocumentStamp = DocumentStamp(),
) {
    fun toDomain(): InterestPolicy = InterestPolicy(householdId, annualRateBasisPoints, accrualDay)

    companion object {
        fun from(policy: InterestPolicy, lamport: Long) =
            InterestPolicyEntity(policy.householdId, policy.annualRateBasisPoints, policy.accrualDay, lamport)
    }
}
