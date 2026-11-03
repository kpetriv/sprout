package dev.kpetriv.sprout.core.crypto

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyPairGeneratorSpi
import java.security.KeyStoreSpi
import java.security.ProviderException
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.ECGenParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.security.Provider as JcaProvider

/**
 * A stand-in for the platform's `AndroidKeyStore` JCA provider under Robolectric, which has none: an in-memory key
 * store plus an EC key pair generator that reads the real [KeyGenParameterSpec]. A spec with an attestation challenge
 * gets a two-certificate chain whose leaf encodes the challenge ([FakeCertificate]); without one, a single
 * "self-signed" certificate, as Keystore makes. [refuseAttestation] makes a challenge fail with [ProviderException]
 * (a Keystore without attestation support); [refuseAll] fails every generation (no biometric enrolled).
 */
internal class FakeAndroidKeyStoreProvider : JcaProvider(AndroidKeystore.PROVIDER, 1.0, "Robolectric stand-in") {
    class Entry(val keyPair: KeyPair, val chain: Array<Certificate>, val spec: KeyGenParameterSpec, val at: Date)

    val stored = LinkedHashMap<String, Entry>()
    val specs = mutableListOf<KeyGenParameterSpec>()

    @Volatile
    var refuseAttestation = false

    @Volatile
    var refuseAll = false

    init {
        val provider = this
        putService(
            object : Service(
                provider,
                "KeyStore",
                AndroidKeystore.PROVIDER,
                FakeKeyStoreSpi::class.java.name,
                null,
                null,
            ) {
                override fun newInstance(constructorParameter: Any?): Any = FakeKeyStoreSpi(provider)
            },
        )
        putService(
            object : Service(provider, "KeyPairGenerator", "EC", FakeEcGeneratorSpi::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = FakeEcGeneratorSpi(provider)
            },
        )
    }

    @Synchronized
    fun generate(spec: KeyGenParameterSpec): KeyPair {
        specs += spec
        val challenge = spec.attestationChallenge
        if (refuseAll) throw ProviderException("Simulated: no biometric enrolled")
        if (challenge != null && refuseAttestation) throw ProviderException("Simulated: attestation unsupported")
        // The JDK's EC generator (the test installs this provider last, so "EC" alone is not answered by it).
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }
        val chain: Array<Certificate> = if (challenge != null) {
            arrayOf(
                FakeCertificate(pair.public, LEAF + challenge),
                FakeCertificate(pair.public, ROOT),
            )
        } else {
            arrayOf(FakeCertificate(pair.public, SELF_SIGNED))
        }
        stored[spec.keystoreAlias] = Entry(pair, chain, spec, Date())
        return pair
    }

    companion object {
        val LEAF: ByteArray = "leaf:".encodeToByteArray()
        val ROOT: ByteArray = "root".encodeToByteArray()
        val SELF_SIGNED: ByteArray = "self-signed".encodeToByteArray()
    }
}

/** Not a real X.509 certificate: only [getEncoded] and [getPublicKey] are read by the code under test. */
internal class FakeCertificate(private val key: PublicKey, private val der: ByteArray) : Certificate("X.509") {
    override fun getEncoded(): ByteArray = der.copyOf()

    override fun verify(key: PublicKey?) = Unit

    override fun verify(key: PublicKey?, sigProvider: String?) = Unit

    override fun toString(): String = "FakeCertificate"

    override fun getPublicKey(): PublicKey = key
}

internal class FakeEcGeneratorSpi(private val provider: FakeAndroidKeyStoreProvider) : KeyPairGeneratorSpi() {
    private var spec: KeyGenParameterSpec? = null

    override fun initialize(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException()

    override fun initialize(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        spec = params as KeyGenParameterSpec
    }

    override fun generateKeyPair(): KeyPair = provider.generate(checkNotNull(spec))
}

internal class FakeKeyStoreSpi(private val provider: FakeAndroidKeyStoreProvider) : KeyStoreSpi() {
    private val entries get() = provider.stored

    override fun engineGetKey(alias: String, password: CharArray?): Key? = entries[alias]?.keyPair?.private

    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = entries[alias]?.chain?.copyOf()

    override fun engineGetCertificate(alias: String): Certificate? = entries[alias]?.chain?.first()

    override fun engineGetCreationDate(alias: String): Date? = entries[alias]?.at

    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) =
        throw UnsupportedOperationException()

    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) =
        throw UnsupportedOperationException()

    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException()

    override fun engineDeleteEntry(alias: String) {
        entries.remove(alias)
    }

    override fun engineAliases(): Enumeration<String> = Collections.enumeration(entries.keys.toList())

    override fun engineContainsAlias(alias: String): Boolean = alias in entries

    override fun engineSize(): Int = entries.size

    override fun engineIsKeyEntry(alias: String): Boolean = alias in entries

    override fun engineIsCertificateEntry(alias: String): Boolean = false

    override fun engineGetCertificateAlias(cert: Certificate?): String? = null

    override fun engineStore(stream: OutputStream?, password: CharArray?) = throw UnsupportedOperationException()

    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}
