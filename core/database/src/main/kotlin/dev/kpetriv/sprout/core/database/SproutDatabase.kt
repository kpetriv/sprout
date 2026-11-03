package dev.kpetriv.sprout.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.kpetriv.sprout.core.database.dao.AccountDao
import dev.kpetriv.sprout.core.database.dao.AnomalyDao
import dev.kpetriv.sprout.core.database.dao.AuditLogDao
import dev.kpetriv.sprout.core.database.dao.BalanceDao
import dev.kpetriv.sprout.core.database.dao.DeviceStateDao
import dev.kpetriv.sprout.core.database.dao.KidDao
import dev.kpetriv.sprout.core.database.dao.LedgerEventDao
import dev.kpetriv.sprout.core.database.dao.OutboxDao
import dev.kpetriv.sprout.core.database.entity.AccountEntity
import dev.kpetriv.sprout.core.database.entity.AnomalyEntity
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.BalanceEntity
import dev.kpetriv.sprout.core.database.entity.DeviceStateEntity
import dev.kpetriv.sprout.core.database.entity.KidEntity
import dev.kpetriv.sprout.core.database.entity.LedgerEventEntity
import dev.kpetriv.sprout.core.database.entity.OutboxEntity

/**
 * Sprout's local database. Encrypted with SQLCipher in production (see [DatabaseFactory]). The schema is exported
 * to `core/database/schemas` and every version bump needs a migration and a `MigrationTest` case.
 */
@Database(
    entities = [
        LedgerEventEntity::class,
        AccountEntity::class,
        KidEntity::class,
        BalanceEntity::class,
        AnomalyEntity::class,
        OutboxEntity::class,
        AuditLogEntity::class,
        DeviceStateEntity::class,
    ],
    version = SproutDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SproutDatabase : RoomDatabase() {
    abstract fun ledgerEventDao(): LedgerEventDao

    abstract fun accountDao(): AccountDao

    abstract fun kidDao(): KidDao

    abstract fun balanceDao(): BalanceDao

    abstract fun anomalyDao(): AnomalyDao

    abstract fun outboxDao(): OutboxDao

    abstract fun auditLogDao(): AuditLogDao

    abstract fun deviceStateDao(): DeviceStateDao

    companion object {
        const val VERSION: Int = 1
    }
}
