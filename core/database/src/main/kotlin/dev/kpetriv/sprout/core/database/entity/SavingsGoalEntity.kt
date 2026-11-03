package dev.kpetriv.sprout.core.database.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainhousehold.SavingsGoal
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.CalendarDate
import dev.kpetriv.sprout.core.model.GoalId
import dev.kpetriv.sprout.core.model.KidId

/**
 * A kid's savings goal ([SavingsGoal]). A synced document. Progress is projected from the SAVINGS balance. A deleted
 * goal stays as a tombstone ([DocumentStamp.deleted]).
 */
@Entity(tableName = "savings_goals", indices = [Index(value = ["kidId"])])
data class SavingsGoalEntity(
    @PrimaryKey val goalId: GoalId,
    val kidId: KidId,
    val title: String,
    val target: Money,
    val createdOn: CalendarDate,
    val achievedOn: CalendarDate?,
    val lamport: Long,
    @Embedded val stamp: DocumentStamp = DocumentStamp(),
) {
    fun toDomain(): SavingsGoal = SavingsGoal(
        id = goalId,
        kidId = kidId,
        title = title,
        target = target,
        createdOn = createdOn,
        achievedOn = achievedOn,
    )

    override fun toString(): String = "SavingsGoalEntity(goalId=$goalId, kidId=$kidId, title=<redacted>)"

    companion object {
        fun from(goal: SavingsGoal, lamport: Long) = SavingsGoalEntity(
            goalId = goal.id,
            kidId = goal.kidId,
            title = goal.title,
            target = goal.target,
            createdOn = goal.createdOn,
            achievedOn = goal.achievedOn,
            lamport = lamport,
        )
    }
}
