package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainhousehold.Chore
import dev.kpetriv.sprout.core.domainhousehold.ChoreReward
import dev.kpetriv.sprout.core.domainledger.Money
import dev.kpetriv.sprout.core.model.ChoreId
import dev.kpetriv.sprout.core.model.ChoreSchedule
import dev.kpetriv.sprout.core.model.ChoreState
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import kotlin.time.Instant

/**
 * A chore definition ([Chore]). A synced document: every write also queues an outbox `DOCUMENT` row.
 *
 * @property scheduleJson the [ChoreSchedule] in its kotlinx-serialization JSON form (`{"type":"weekly",...}`).
 * @property lamport the device Lamport value of the last local write (last-writer-wins key for sync, M3).
 */
@Entity(tableName = "chores", indices = [Index(value = ["kidId"])])
data class ChoreEntity(
    @PrimaryKey val choreId: ChoreId,
    val householdId: HouseholdId,
    val kidId: KidId,
    val title: String,
    val description: String?,
    val rewardMoney: Money?,
    val rewardMinutes: Money?,
    val scheduleJson: String,
    val state: ChoreState,
    val createdAtEpochMs: Long,
    val lamport: Long,
) {
    fun toDomain(): Chore = Chore(
        id = choreId,
        householdId = householdId,
        kidId = kidId,
        title = title,
        description = description,
        reward = ChoreReward(money = rewardMoney, minutes = rewardMinutes),
        schedule = DocumentJson.json.decodeFromString(ChoreSchedule.serializer(), scheduleJson),
        createdAt = Instant.fromEpochMilliseconds(createdAtEpochMs),
        state = state,
    )

    override fun toString(): String = "ChoreEntity(choreId=$choreId, kidId=$kidId, state=$state, title=<redacted>)"

    companion object {
        fun from(chore: Chore, lamport: Long) = ChoreEntity(
            choreId = chore.id,
            householdId = chore.householdId,
            kidId = chore.kidId,
            title = chore.title,
            description = chore.description,
            rewardMoney = chore.reward.money,
            rewardMinutes = chore.reward.minutes,
            scheduleJson = DocumentJson.json.encodeToString(ChoreSchedule.serializer(), chore.schedule),
            state = chore.state,
            createdAtEpochMs = chore.createdAt.toEpochMilliseconds(),
            lamport = lamport,
        )
    }
}
