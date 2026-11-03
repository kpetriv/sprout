package dev.kpetriv.sprout.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.kpetriv.sprout.core.domainledger.Currency

/**
 * The household's local ledger policy; a single row with [id] [SINGLETON_ID]. Device-local in M2 (the household
 * registers its policy and parent keys with the device registry in M3).
 *
 * @property requireParentSignature parent-only events need a verified parent signature (ADR-0004). True once a
 *   parent key is registered; false on a device without a strong biometric (D-M2-1).
 * @property parentSignatureFromLamport only events at or above this lamport need the signature: the lamport at
 *   which the parent key was registered, so earlier unsigned parent events stay valid.
 * @property parentPublicKey the parent authentication key, X.509 SubjectPublicKeyInfo DER (public, not secret).
 * @property fiatCurrency the household's money currency.
 */
@Entity(tableName = "household_policy")
data class HouseholdPolicyEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val requireParentSignature: Boolean,
    val parentSignatureFromLamport: Long?,
    val parentPublicKey: ByteArray?,
    val fiatCurrency: Currency,
    val createdAtEpochMs: Long,
) {
    override fun equals(other: Any?): Boolean = other is HouseholdPolicyEntity && id == other.id &&
        requireParentSignature == other.requireParentSignature &&
        parentSignatureFromLamport == other.parentSignatureFromLamport &&
        (parentPublicKey?.contentEquals(other.parentPublicKey) ?: (other.parentPublicKey == null)) &&
        fiatCurrency == other.fiatCurrency && createdAtEpochMs == other.createdAtEpochMs

    override fun hashCode(): Int = id

    companion object {
        const val SINGLETON_ID: Int = 0
    }
}
