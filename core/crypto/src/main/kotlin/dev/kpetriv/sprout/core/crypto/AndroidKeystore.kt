package dev.kpetriv.sprout.core.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import java.security.Key
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlin.time.Instant
import android.security.keystore.KeyProperties as AndroidKeyProperties

/**
 * Shared Android Keystore plumbing: StrongBox-first key generation with TEE fallback, and key property lookup.
 * Keys created here are non-exportable; only handles enter the app process.
 */
internal class AndroidKeystore(private val context: Context) {
    val keyStore: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    private val hasStrongBox: Boolean by lazy {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    }

    /**
     * Builds a spec with [configure] and generates the key, in StrongBox when the device has one. If StrongBox
     * refuses (no secure element, it cannot hold this key type, or Keystore reports a provider or keystore error)
     * any partial entry under [alias] is deleted and generation repeats in the TEE. See [withStrongBoxFallback].
     */
    fun <T> generate(
        alias: String,
        purposes: Int,
        configure: KeyGenParameterSpec.Builder.() -> Unit,
        generator: (KeyGenParameterSpec) -> T,
    ): T {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, purposes)
            .apply(configure)
            .setIsStrongBoxBacked(strongBox)
            .build()

        return withStrongBoxFallback(
            useStrongBox = hasStrongBox,
            strongBox = { generator(spec(strongBox = true)) },
            tee = { generator(spec(strongBox = false)) },
            cleanUp = { delete(alias) },
        )
    }

    fun privateKey(alias: String): PrivateKey? = keyStore.getKey(alias, null) as? PrivateKey

    fun secretKey(alias: String): SecretKey? = keyStore.getKey(alias, null) as? SecretKey

    fun delete(alias: String) {
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    fun properties(alias: String, key: Key): DeviceKeyProperties {
        val info = when (key) {
            is PrivateKey -> KeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java)

            is SecretKey -> SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java)
                as KeyInfo

            else -> error("Unsupported key type")
        }
        val createdAt = keyStore.getCreationDate(alias)?.let { Instant.fromEpochMilliseconds(it.time) }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val level = info.securityLevel
            DeviceKeyProperties(
                isHardwareBacked = level == AndroidKeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                    level == AndroidKeyProperties.SECURITY_LEVEL_STRONGBOX ||
                    level == AndroidKeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE,
                isStrongBox = level == AndroidKeyProperties.SECURITY_LEVEL_STRONGBOX,
                createdAt = createdAt,
            )
        } else {
            @Suppress("DEPRECATION")
            DeviceKeyProperties(
                isHardwareBacked = info.isInsideSecureHardware,
                isStrongBox = false,
                createdAt = createdAt,
            )
        }
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
    }
}
