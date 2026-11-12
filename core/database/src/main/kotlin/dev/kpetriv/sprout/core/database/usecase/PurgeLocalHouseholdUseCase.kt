package dev.kpetriv.sprout.core.database.usecase

import androidx.room.withTransaction
import dev.kpetriv.sprout.core.common.Dispatcher
import dev.kpetriv.sprout.core.common.SproutDispatchers
import dev.kpetriv.sprout.core.database.LocalDataFiles
import dev.kpetriv.sprout.core.database.LocalPreferenceStore
import dev.kpetriv.sprout.core.database.LocalPreferenceStores
import dev.kpetriv.sprout.core.database.LocalTrial
import dev.kpetriv.sprout.core.database.SproutDatabase
import dev.kpetriv.sprout.core.database.entity.AuditAction
import dev.kpetriv.sprout.core.database.entity.AuditLogEntity
import dev.kpetriv.sprout.core.database.entity.OutboxEntity
import dev.kpetriv.sprout.core.database.entity.OutboxKind
import dev.kpetriv.sprout.core.domainledger.LedgerJson
import dev.kpetriv.sprout.core.domainledger.chat.MessageJson
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.sync.DocumentJsonCodec
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.inject.Inject

/** What [PurgeLocalHouseholdUseCase] deleted, per table (counts only). */
data class PurgeReport(val counts: Map<String, Int>) {
    val total: Int get() = counts.values.sum()
}

/**
 * Deletes the pre-linking local household (`householdId == "local"`, [LocalTrial]) when this device joins a real
 * household (ADR-0004 §2, M3 §6), after the user confirmed the "your trial data will be deleted" screen.
 *
 * One transaction removes: its kids and their accounts, ledger events, balances, anomalies, chores, submissions,
 * rewards, allowance schedules, savings goals, interest policy, pending parent actions and photo rows; its chat
 * messages, message sequence numbers, thread keys, read marks and cached blobs (M4; the trial household never syncs, so
 * these are normally empty); the household
 * policy (this device's view of the trial household, including the locally registered parent key: the parent
 * registers again in the real household) and the trial parent's `parent_keys` rows; its pull cursors; outbox rows
 * and held inbox rows of the trial household; the audit rows its
 * trial parent (and, before M3, its kids) wrote; and the trial session. It then writes one
 * `LOCAL_HOUSEHOLD_PURGED` audit row with the counts. The photo and blob files are deleted after the transaction
 * commits, and every [LocalPreferenceStore] (the kids' Times tables setup) is cleared.
 * Nothing of a real household is touched; `device_state` (the Lamport clock, which only moves forward) is kept.
 */
class PurgeLocalHouseholdUseCase @Inject constructor(
    private val database: SproutDatabase,
    private val files: LocalDataFiles,
    private val clock: Clock,
    @param:Dispatcher(SproutDispatchers.IO) private val dispatcher: CoroutineDispatcher,
    private val preferences: LocalPreferenceStores = LocalPreferenceStores.None,
) {
    suspend operator fun invoke(): PurgeReport {
        val purged = database.withTransaction { purge() }
        // The trial kids' local preferences (their Times tables setup) go with them.
        preferences.clearAll()
        withContext(dispatcher) {
            deleteIn(files.photoDirectory, purged.photoFiles)
            deleteIn(files.blobDirectory, purged.blobFiles)
        }
        return purged.report
    }

    private fun deleteIn(directory: File, names: List<String>) {
        val canonical = directory.canonicalFile
        names.map { File(canonical, it) }
            .filter { it.canonicalFile.parentFile == canonical }
            .forEach { it.delete() }
    }

    private class Purged(val report: PurgeReport, val photoFiles: List<String>, val blobFiles: List<String>)

    private suspend fun purge(): Purged {
        val household = LocalTrial.HOUSEHOLD
        val purge = database.purgeDao()
        val kids = purge.kidsOf(household)
        val threads = kids.map { it.value }
        val photos = purge.photosOf(kids)
        val blobs = database.blobDao().all().filter { blobHousehold(it.contextJson) in setOf(null, household.value) }
        val counts = linkedMapOf(
            "ledger_events" to purge.deleteEvents(household),
            "balances" to purge.deleteBalances(kids),
            "anomalies" to purge.deleteAnomalies(kids),
            "chores" to purge.deleteChores(household, kids),
            "chore_submissions" to purge.deleteSubmissions(kids),
            "rewards" to purge.deleteRewards(household),
            "allowance_schedules" to purge.deleteAllowances(kids),
            "savings_goals" to purge.deleteGoals(kids),
            "interest_policy" to purge.deleteInterestPolicy(household),
            "pending_parent_actions" to purge.deletePendingActions(kids),
            "photos" to purge.deletePhotos(kids),
            "messages" to purge.deleteMessages(household, threads),
            "message_seq" to purge.deleteMessageSeqs(threads),
            "thread_keys" to purge.deleteThreadKeys(threads),
            "read_marks" to purge.deleteReadMarks(threads),
            "time_bank_clocks" to database.timeBankClockDao().deleteForKids(threads),
            "blobs" to blobs.sumOf { database.blobDao().delete(it.blobId) },
            "accounts" to purge.deleteAccounts(kids),
            "kids" to purge.deleteKids(household),
            "household_policy" to purge.deleteHouseholdPolicy(),
            "parent_keys" to purge.deleteParentKeys(LocalTrial.PARENT),
            "sync_cursor" to database.syncCursorDao().deleteFor(household),
            "outbox" to purge.deleteOutbox(
                database.outboxDao().all().filter {
                    it.belongsTo(household)
                }.map { it.seq },
            ),
            "ledger_inbox" to purgeLedgerInbox(household),
            "document_inbox" to purgeDocumentInbox(household),
            "audit_log" to purge.deleteAudit(listOf(LocalTrial.PARENT) + kids.map { UserId(it.value) }),
            "session" to purge.deleteSession(household),
        )
        val report = PurgeReport(counts)
        writeAudit(report)
        return Purged(report, photos, blobs.map { it.fileName })
    }

    /** The household a cached blob's context names, or null when the context no longer parses. */
    private fun blobHousehold(contextJson: String): String? = try {
        val context = LedgerJson.json.parseToJsonElement(contextJson) as? JsonObject
        (context?.get("householdId") as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (expected: IllegalArgumentException) {
        null
    }

    private suspend fun purgeLedgerInbox(household: HouseholdId): Int {
        val inbox = database.ledgerInboxDao()
        return inbox.all().filter { householdOfEvent(it.payloadJson) == household.value }
            .sumOf { inbox.delete(it.eventId, it.hash) }
    }

    private suspend fun purgeDocumentInbox(household: HouseholdId): Int {
        val inbox = database.documentInboxDao()
        return inbox.all().filter { DocumentJsonCodec.decodeOrNull(it.envelopeJson)?.householdId == household }
            .sumOf { inbox.delete(it.docType, it.docId) }
    }

    /** True for rows of [household] and for rows that no longer decode (they can never be sent). */
    private fun OutboxEntity.belongsTo(household: HouseholdId): Boolean = when (kind) {
        OutboxKind.LEDGER_EVENT -> householdOfEvent(payload).let { it == null || it == household.value }

        OutboxKind.DOCUMENT -> DocumentJsonCodec.decodeOrNull(payload)?.householdId.let {
            it == null || it == household
        }

        OutboxKind.MESSAGE -> MessageJson.decodeOrNull(payload)?.householdId.let { it == null || it == household }

        OutboxKind.BLOB_CHUNK -> blobHousehold(payload).let { it == null || it == household.value }
    }

    private fun householdOfEvent(json: String): String? = try {
        val envelope = (LedgerJson.json.parseToJsonElement(json) as? JsonObject)?.get("envelope") as? JsonObject
        (envelope?.get("householdId") as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (expected: IllegalArgumentException) {
        null
    }

    private suspend fun writeAudit(report: PurgeReport) {
        val session = database.sessionDao().get()
        val details = buildJsonObject {
            report.counts.forEach { (table, count) -> put(table, count) }
        }
        database.auditLogDao().insert(
            AuditLogEntity(
                atEpochMs = clock.now().toEpochMilliseconds(),
                actorUid = session?.uid ?: LocalTrial.PARENT,
                deviceId = session?.deviceId ?: database.deviceStateDao().get()?.deviceId ?: DeviceId(UNKNOWN_DEVICE),
                action = AuditAction.LOCAL_HOUSEHOLD_PURGED,
                target = LocalTrial.HOUSEHOLD.value,
                detailsJson = LedgerJson.json.encodeToString(JsonObject.serializer(), details),
            ),
        )
    }

    private companion object {
        const val UNKNOWN_DEVICE = "unknown"
    }
}
