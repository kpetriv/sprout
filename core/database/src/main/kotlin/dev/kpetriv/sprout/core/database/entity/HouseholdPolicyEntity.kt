package dev.kpetriv.sprout.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainledger.Currency

/**
 * This device's view of the household policy; a single row with [id] [SINGLETON_ID]. The synced part (fiat currency,
 * `requireParentSignature`, chat retention and the game cap) is the `household_policy` document ([lamport],
 * [stamp]); the parent key fields are this device's own registration, used to verify parent signatures on **local**
 * appends (M3 §2). Remote events are verified against the registry (`parent_keys`) instead.
 *
 * @property requireParentSignature parent-only events need a verified parent signature (ADR-0004). True once a
 *   parent key is registered; false on a device without a strong biometric (D-M2-1).
 * @property parentSignatureFromLamport only events at or above this lamport need the signature: the lamport at
 *   which the parent key was registered, so earlier unsigned parent events stay valid.
 * @property parentPublicKey the parent authentication key, X.509 SubjectPublicKeyInfo DER (public, not secret).
 * @property fiatCurrency the household's money currency.
 * @property chatRetentionDays keep chat messages this many days (1 to 3650), null to keep them (schema version 8,
 *   D-M7-10); synced.
 * @property dailyGameCapMinor the daily game cap in minor units (0 to 10,000), null for the ledger default of 50
 *   (schema version 8, D-M5-4); synced, so every device projects the same exclusions (ADR-0009).
 * @property lamport the household policy document's lamport (schema version 3); 0 for rows written before.
 * @property stamp author and signature of the household policy document (schema version 3).
 */
@Entity(tableName = "household_policy")
data class HouseholdPolicyEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val requireParentSignature: Boolean,
    val parentSignatureFromLamport: Long?,
    val parentPublicKey: ByteArray?,
    val fiatCurrency: Currency,
    val createdAtEpochMs: Long,
    @ColumnInfo(defaultValue = "0") val lamport: Long = 0,
    @Embedded val stamp: DocumentStamp = DocumentStamp(),
    val chatRetentionDays: Int? = null,
    val dailyGameCapMinor: Long? = null,
) {
    override fun equals(other: Any?): Boolean = other is HouseholdPolicyEntity && id == other.id &&
        requireParentSignature == other.requireParentSignature &&
        parentSignatureFromLamport == other.parentSignatureFromLamport &&
        (parentPublicKey?.contentEquals(other.parentPublicKey) ?: (other.parentPublicKey == null)) &&
        fiatCurrency == other.fiatCurrency && createdAtEpochMs == other.createdAtEpochMs && lamport == other.lamport &&
        stamp == other.stamp && chatRetentionDays == other.chatRetentionDays &&
        dailyGameCapMinor == other.dailyGameCapMinor

    override fun hashCode(): Int = id

    companion object {
        const val SINGLETON_ID: Int = 0
    }
}
