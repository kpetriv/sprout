package dev.kpetriv.sprout.feature.lock

import dev.kpetriv.sprout.core.crypto.BootClock
import dev.kpetriv.sprout.core.crypto.FakeBootClock
import dev.kpetriv.sprout.core.crypto.FakeParentAuthorizer
import dev.kpetriv.sprout.core.crypto.InMemoryHmacPepper
import dev.kpetriv.sprout.core.crypto.InMemoryLockoutStateStore
import dev.kpetriv.sprout.core.crypto.ParentSigner
import dev.kpetriv.sprout.core.crypto.Pbkdf2PinHasher
import dev.kpetriv.sprout.core.crypto.PinLockoutPolicy
import dev.kpetriv.sprout.core.crypto.PublicKeyBytes
import dev.kpetriv.sprout.core.time.FakeClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.IOException
import java.security.SecureRandom
import java.security.Signature
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** In-memory [ParentPinStore]. [unreadable] simulates a stored hash that cannot be decrypted. */
class InMemoryParentPinStore(initial: String? = null) : ParentPinStore {
    @Volatile
    var hash: String? = initial

    @Volatile
    var unreadable = false

    @Volatile
    var failWrites = false

    var saves = 0
        private set

    override suspend fun hasPin(): Boolean = hash != null || unreadable

    override suspend fun storedHash(): String? {
        if (unreadable) throw ParentPinUnavailableException("Simulated unreadable PIN hash")
        return hash
    }

    override suspend fun save(hash: String) {
        if (failWrites) throw IOException("Simulated write failure")
        this.hash = hash
        saves++
    }

    override suspend fun clear() {
        if (failWrites) throw IOException("Simulated write failure")
        hash = null
    }
}

class FakeLockSettings(lockWhenLeaving: Boolean = true) : LockSettings {
    private val flow = MutableStateFlow(lockWhenLeaving)
    override val lockWhenLeaving: StateFlow<Boolean> = flow

    override fun setLockWhenLeaving(enabled: Boolean) {
        flow.value = enabled
    }
}

class FakeAppForeground(inForeground: Boolean = true) : AppForegroundSource {
    val foreground = MutableStateFlow(inForeground)
    override val isInForeground = foreground
}

/** In-memory [ParentKeyRegistry]: the household's registered parent key. */
class FakeParentKeyRegistry : ParentKeyRegistry {
    @Volatile
    var key: PublicKeyBytes? = null

    /** The D-M2-1 state was recorded (a policy with signatures off exists). */
    @Volatile
    var signingUnavailableRecorded = false

    @Volatile
    var unreadable = false

    var registrations = 0
        private set
    var replacements = 0
        private set

    /** The attestation chain of the latest registration or replacement. */
    var lastChain: List<ByteArray>? = null
        private set

    override suspend fun registeredParentKey(): PublicKeyBytes? {
        if (unreadable) throw IOException("Simulated unreadable household policy")
        return key
    }

    override suspend fun registerParentKey(publicKey: PublicKeyBytes, attestationChain: List<ByteArray>) {
        check(key == null) { "A parent key is already registered" }
        key = publicKey
        lastChain = attestationChain
        registrations++
    }

    override suspend fun replaceParentKey(publicKey: PublicKeyBytes, attestationChain: List<ByteArray>) {
        checkNotNull(key) { "No parent key is registered" }
        key = publicKey
        lastChain = attestationChain
        replacements++
    }

    /** What [householdPolicyReady] answers (D-REL-15); asked counts the calls. */
    @Volatile
    var policyReady = true

    var policyChecks = 0
        private set

    override suspend fun householdPolicyReady(): Boolean {
        policyChecks++
        return policyReady
    }

    override suspend fun recordSigningUnavailable() {
        signingUnavailableRecorded = true
    }
}

/**
 * The server's challenge, as the app's `beginDeviceRegistration` adapter would give it: [next] (null: no household
 * session), or a failure when [failure] is set. [issued] lists every challenge handed out.
 */
class FakeChallengeSource : ParentKeyChallengeSource {
    var next: ByteArray? = ByteArray(32) { (it + 1).toByte() }
    var failure: Exception? = null
    val issued = mutableListOf<ByteArray>()

    /** Called with the challenge just issued, before the key is created (to check the order of calls). */
    var onIssued: () -> Unit = {}

    override suspend fun registrationChallenge(): ByteArray? {
        failure?.let { throw it }
        return next?.copyOf()?.also {
            issued += it
            onIssued()
        }
    }
}

/**
 * Stands in for the proof prompt. By default it "authenticates" the signature it is given, like a successful
 * `BiometricPrompt`; set [answer] to throw (cancel, error) or to return something else.
 */
class FakeProofPrompter : ParentKeyProofPrompter {
    var prompts = 0
        private set

    var answer: (Signature) -> Signature = { it }

    /** When set, the prompt stays open until it completes (the parent has not touched the sensor yet). */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun prompt(signature: Signature): Signature {
        prompts++
        gate?.await()
        return answer(signature)
    }
}

/** A [ParentSession] over in-memory fakes, with the collaborators exposed for assertions. */
class SessionFixture(
    private val scope: CoroutineScope,
    pin: String? = null,
    lockWhenLeaving: Boolean = true,
    /** Store a retired M1-format (`v1`, unpeppered) hash of this PIN instead of [pin]. */
    legacyPin: String? = null,
    /** Store a peppered hash of this PIN with fewer iterations than the hasher uses, so it needs a rehash. */
    weakPin: String? = null,
    /** A PIN hash is stored but cannot be decrypted. */
    unreadablePin: Boolean = false,
    /** [FakeBootClock] by default; ViewModel tests pass a [TestSchedulerBootClock] so delays and the clock agree. */
    val bootClock: BootClock = FakeBootClock(),
    dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) {
    val random = SecureRandom()
    private val pepper = InMemoryHmacPepper(random)
    val hasher = Pbkdf2PinHasher(random, dispatcher, pepper, iterations = TEST_ITERATIONS)
    val pinStore = InMemoryParentPinStore()
    val lockoutStore = InMemoryLockoutStateStore()
    val fakeBootClock: FakeBootClock get() = bootClock as FakeBootClock
    val clock = FakeClock()
    val authorizer = FakeParentAuthorizer()
    val registry = FakeParentKeyRegistry()
    val keyStatus = ParentKeyStatusCheck(authorizer, registry)
    val lockout = PinLockoutPolicy(lockoutStore, bootClock, keyStatus)
    val prompter = FakeProofPrompter()
    val challenges = FakeChallengeSource()
    val settings = FakeLockSettings(lockWhenLeaving)
    val foreground = FakeAppForeground()
    val uploader = FakeRecoveryCheckUploader()
    val recoveryFlag = InMemoryRecoveryPinFlag()
    val recoveryPin = RecoveryPinRegistration(uploader, recoveryFlag, clock, scope)

    init {
        if (pin != null) kotlinx.coroutines.runBlocking { pinStore.hash = hasher.hash(pin.toCharArray()) }
        if (legacyPin != null) pinStore.hash = legacyHash(legacyPin)
        if (weakPin != null) {
            val weak = Pbkdf2PinHasher(random, dispatcher, pepper, iterations = TEST_ITERATIONS / 2)
            kotlinx.coroutines.runBlocking { pinStore.hash = weak.hash(weakPin.toCharArray()) }
        }
        pinStore.unreadable = unreadablePin
    }

    val session = ParentSession(
        pinStore = pinStore,
        hasher = hasher,
        lockout = lockout,
        authorizer = authorizer,
        registry = registry,
        keyStatus = keyStatus,
        signer = ParentSigner(),
        settings = settings,
        clock = clock,
        bootClock = bootClock,
        random = random,
        recoveryPin = recoveryPin,
        appForeground = foreground,
        scope = scope,
        ioDispatcher = dispatcher,
    )

    internal val provisioner = ParentKeyProvisioner(
        session = session,
        authorizer = authorizer,
        signer = ParentSigner(),
        registry = registry,
        challenges = challenges,
        prompter = prompter,
        random = random,
        scope = scope,
        ioDispatcher = dispatcher,
    )
    val enrolment = ParentKeyEnrolment(provisioner)
    val renewal = ParentKeyRenewal(provisioner)

    fun entryViewModel() = PinEntryViewModel(session, renewal)

    fun setupViewModel() = PinSetupViewModel(session, enrolment)

    /**
     * The state after a completed enrolment, without going through it: a parent key exists on this device and is
     * the household's registered key.
     */
    suspend fun enrolKeyDirectly(): PublicKeyBytes = authorizer.createParentKey().also { registry.key = it }

    /** A retired M1-format (`v1`, unpeppered) hash of [pin]. */
    fun legacyHash(pin: String): String {
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val spec = PBEKeySpec(pin.toCharArray(), salt, TEST_ITERATIONS, HASH_BITS)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val encoder = Base64.getEncoder().withoutPadding()
        return "v1\$pbkdf2-sha256\$i=$TEST_ITERATIONS\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    companion object {
        const val PIN = "482913"
        const val WRONG_PIN = "482914"
        private const val TEST_ITERATIONS = 1_000
        private const val SALT_SIZE = 16
        private const val HASH_BITS = 256
    }
}

/** Elapsed realtime that follows the coroutine test scheduler's virtual time, so `delay` and the lockout agree. */
class TestSchedulerBootClock(private val scheduler: TestCoroutineScheduler) : BootClock {
    override fun elapsedRealtime(): Duration = START + scheduler.currentTime.milliseconds

    override fun bootId(): String = "boot-1"

    private companion object {
        val START = 10_000.milliseconds
    }
}

/**
 * The app's check value upload (ADR-0013 §1): records each PIN it was given (as a String, test only) and answers
 * [result]; [gate], when set, holds every upload until completed.
 */
class FakeRecoveryCheckUploader : RecoveryCheckUploader {
    val uploads = mutableListOf<String>()

    /** The `newPin` flag of each upload. */
    val newPins = mutableListOf<Boolean>()

    @Volatile
    var result: RecoveryUploadResult = RecoveryUploadResult.Uploaded(stored = true, attemptsLeft = 10)

    @Volatile
    var gate: CompletableDeferred<Unit>? = null

    /** The arrays handed over, to check they were zeroed afterwards. */
    val arrays = mutableListOf<CharArray>()

    override suspend fun upload(pin: CharArray, newPin: Boolean): RecoveryUploadResult {
        uploads += String(pin)
        newPins += newPin
        arrays += pin
        gate?.await()
        return result
    }
}

class InMemoryRecoveryPinFlag(initial: RecoveryPinMarks = RecoveryPinMarks()) : RecoveryPinFlag {
    private val state = MutableStateFlow(initial)

    /** Every value written, in order: true when a registration was written, false otherwise. */
    val writes = mutableListOf<Boolean>()

    override val marks: Flow<RecoveryPinMarks> = state

    override suspend fun marks(): RecoveryPinMarks = state.value

    override suspend fun setMarks(marks: RecoveryPinMarks) {
        writes += marks.registered != null
        state.value = marks
    }

    override suspend fun clear() {
        state.value = RecoveryPinMarks()
    }

    fun isRegistered(): Boolean = state.value.registered != null

    fun registeredAt(): Instant? = state.value.registered?.at

    fun registration(): RecoveryRegistration? = state.value.registered
}
