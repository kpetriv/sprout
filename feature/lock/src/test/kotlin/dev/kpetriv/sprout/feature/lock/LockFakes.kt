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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    override suspend fun registeredParentKey(): PublicKeyBytes? {
        if (unreadable) throw IOException("Simulated unreadable household policy")
        return key
    }

    override suspend fun registerParentKey(publicKey: PublicKeyBytes) {
        check(key == null) { "A parent key is already registered" }
        key = publicKey
        registrations++
    }

    override suspend fun replaceParentKey(publicKey: PublicKeyBytes) {
        checkNotNull(key) { "No parent key is registered" }
        key = publicKey
        replacements++
    }

    override suspend fun recordSigningUnavailable() {
        signingUnavailableRecorded = true
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

    override suspend fun prompt(signature: Signature): Signature {
        prompts++
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
    val settings = FakeLockSettings(lockWhenLeaving)
    val foreground = FakeAppForeground()

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
        appForeground = foreground,
        scope = scope,
        ioDispatcher = dispatcher,
    )

    private val provisioner = ParentKeyProvisioner(
        session = session,
        authorizer = authorizer,
        signer = ParentSigner(),
        registry = registry,
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
