package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.YearMonth

/**
 * A kid profile, exactly the fields of [Kid] (data minimisation: first name, avatar, birth month, tier). No
 * other personal data is stored.
 */
@Entity(tableName = "kids", indices = [Index(value = ["householdId"])])
data class KidEntity(
    @PrimaryKey val kidId: KidId,
    val householdId: HouseholdId,
    val firstName: String,
    val avatarId: String,
    val birthYearMonth: YearMonth,
    val uiTier: KidUiTier,
    val archived: Boolean,
) {
    fun toDomain(): Kid = Kid(
        id = kidId,
        householdId = householdId,
        firstName = firstName,
        avatarId = avatarId,
        birthYearMonth = birthYearMonth,
        uiTier = uiTier,
        archived = archived,
    )

    override fun toString(): String = "KidEntity(kidId=$kidId, firstName=<redacted>, birthYearMonth=<redacted>)"

    companion object {
        fun from(kid: Kid) = KidEntity(
            kidId = kid.id,
            householdId = kid.householdId,
            firstName = kid.firstName,
            avatarId = kid.avatarId,
            birthYearMonth = kid.birthYearMonth,
            uiTier = kid.uiTier,
            archived = kid.archived,
        )
    }
}
