package dev.kpetriv.sprout.feature.lock

import android.content.Context
import androidx.core.content.edit
import dev.kpetriv.sprout.core.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** How [RecoveryCheckUploader.upload] ended. */
sealed interface RecoveryUploadResult {
    /**
     * The server answered: [stored] true when it holds this PIN's check value now, false when it kept the value another
     * working phone of the account set (an unchanged PIN registered again); [attemptsLeft] (0..10) of the value it
     * holds.
     */
    data class Uploaded(val stored: Boolean, val attemptsLeft: Int) : RecoveryUploadResult

    /** Nothing to register here: no signed-in parent with an ACTIVE device (the trial household, a waiting phone). */
    data object NotApplicable : RecoveryUploadResult

    /** Offline, or the derivation failed: try again at the next correct PIN. */
    data object Failed : RecoveryUploadResult

    /** The server refused the call (not a network problem): try again at the next correct PIN. */
    data object Refused : RecoveryUploadResult
}

/**
 * Stores the server's check value of the parent PIN (ADR-0013 §1): derives it (`RecoveryPinVerifier`, slow) and calls
 * `setRecoveryPin` signed by this ACTIVE parent device, with [newPin] true when the PIN was just chosen or changed (the
 * server restarts its failed attempts and replaces any value) and false for an unchanged PIN registered again (the
 * count is kept, and another working phone's value too). The app implements it over core/sync, which this module does
 * not depend on. Must not zero [pin] (the caller does) and must never log anything derived from it.
 */
fun interface RecoveryCheckUploader {
    suspend fun upload(pin: CharArray, newPin: Boolean): RecoveryUploadResult
}

/** One answer of the server: when ([at]) and what was left of the wrong-PIN budget ([attemptsLeft], 0..10). */
data class RecoveryRegistration(val at: Instant, val attemptsLeft: Int)

/**
 * The recovery marks this phone keeps (ADR-0013 §1), none of them derived from the PIN:
 * - [registered]: the last upload whose value the server stored (the check value matches this phone's PIN);
 * - [keptOther]: the last upload the server answered with `stored: false` (the account restores with the PIN set or
 *   changed last, on another working phone);
 * - [newPinPending]: "new PIN not uploaded yet": a PIN change whose `newPin: true` upload has not gone through. While
 *   it is set, every upload says `newPin: true`.
 */
data class RecoveryPinMarks(
    val registered: RecoveryRegistration? = null,
    val keptOther: RecoveryRegistration? = null,
    val newPinPending: Boolean = false,
)

/** Where [RecoveryPinMarks] live, wiped with the household's local data (the app registers [clear] with the wipe). */
interface RecoveryPinFlag {
    /** The marks as they change. */
    val marks: Flow<RecoveryPinMarks>

    suspend fun marks(): RecoveryPinMarks

    /** Replaces the marks; durable when it returns. */
    suspend fun setMarks(marks: RecoveryPinMarks)

    /** Removes every mark. */
    suspend fun clear()
}

/**
 * Keeps the server's recovery PIN check value in step with the parent PIN (ADR-0013 §1), from [ParentSession], the
 * only place that sees the PIN:
 * - a PIN change (set-up, change): [beforePinChange] clears the marks **before** the new hash is stored and, unless
 *   the "new" PIN is the PIN it was, sets "new PIN not uploaded yet"; [afterPinChange] then registers it;
 * - a correct PIN entry ([pinVerified]): registers it unless an answer younger than [REFRESH_AFTER] is stored (stored
 *   or kept-other) and no new PIN is waiting to go up (households from before 1.1.1, a failed upload, the refresh);
 * - "finish setting up" in Settings ([registerNow]) waits for the result;
 * - a restore with the PIN ([markRegistered]): the server's value already matches, so nothing is uploaded;
 * - the wipe and a new PENDING registration ([forget]) remove every mark.
 *
 * Every upload says `newPin: true` exactly while "new PIN not uploaded yet" is set. Uploads run in the background on
 * [scope], one at a time, never on the caller's (UI) path, and failures are silent: the next correct PIN tries again.
 * Each PIN change, restore or wipe starts a new generation; an upload of an older generation never writes a mark, and
 * the check and the write happen under one lock with [forget], so an upload still running cannot write a mark back
 * after a wipe. Every PIN array handed over is zeroed here once its upload ends. Nothing derived from the PIN is kept,
 * and nothing is logged.
 */
@Singleton
class RecoveryPinRegistration @Inject constructor(
    private val uploader: RecoveryCheckUploader,
    private val flag: RecoveryPinFlag,
    private val clock: Clock,
    @param:LockScope private val scope: CoroutineScope,
) {
    private val uploads = Mutex()
    private val marksLock = Mutex()
    private val generation = AtomicLong()

    /** The marks, for Settings. */
    val marks: Flow<RecoveryPinMarks> get() = flag.marks

    /**
     * A PIN is about to be stored: clears the marks and, unless [samePin] (the "new" PIN is the PIN it was), sets "new
     * PIN not uploaded yet" (one already set stays). Returns the token for [afterPinChange].
     */
    suspend fun beforePinChange(samePin: Boolean): Long = marksLock.withLock {
        val token = generation.incrementAndGet()
        val pending = attempt { flag.marks().newPinPending } == true
        attempt { flag.setMarks(RecoveryPinMarks(newPinPending = pending || !samePin)) }
        token
    }

    /** The PIN of [token] is stored: registers it in the background. Takes [pin] and zeroes it. */
    fun afterPinChange(pin: CharArray, token: Long) {
        launchUpload(pin, token, onlyIfStale = false)
    }

    /** A correct PIN was entered: registers it in the background when it should. Takes [pin] and zeroes it. */
    fun pinVerified(pin: CharArray) {
        launchUpload(pin, generation.get(), onlyIfStale = true)
    }

    /** Registers [pin] now and returns the outcome (Settings' row). Zeroes [pin]. */
    suspend fun registerNow(pin: CharArray): RecoveryUploadResult = try {
        upload(pin, generation.get(), onlyIfStale = false)
    } finally {
        pin.fill(ZERO_CHAR)
    }

    /**
     * The PIN just stored is the one the server's check value was made from (a restore with the PIN), with
     * [attemptsLeft] as the restore answered. Durable when it returns.
     */
    suspend fun markRegistered(attemptsLeft: Int) = marksLock.withLock {
        generation.incrementAndGet()
        attempt { flag.setMarks(RecoveryPinMarks(registered = RecoveryRegistration(clock.now(), attemptsLeft))) }
        Unit
    }

    /** Removes every mark (the wipe, a new PENDING registration); an upload still running writes none afterwards. */
    suspend fun forget() = marksLock.withLock {
        generation.incrementAndGet()
        flag.clear()
    }

    private fun launchUpload(pin: CharArray, token: Long, onlyIfStale: Boolean) {
        scope.launch {
            try {
                upload(pin, token, onlyIfStale)
            } finally {
                pin.fill(ZERO_CHAR)
            }
        }
    }

    private suspend fun upload(pin: CharArray, token: Long, onlyIfStale: Boolean): RecoveryUploadResult =
        uploads.withLock {
            val marks = attempt { flag.marks() } ?: RecoveryPinMarks()
            val fresh = marks.registered?.takeIf { isFresh(it.at) } ?: marks.keptOther?.takeIf { isFresh(it.at) }
            when {
                // A newer PIN, a restore or a wipe came meanwhile: nothing of this upload applies any more.
                token != generation.get() -> RecoveryUploadResult.NotApplicable

                onlyIfStale && !marks.newPinPending && fresh != null ->
                    RecoveryUploadResult.Uploaded(stored = marks.registered != null, attemptsLeft = fresh.attemptsLeft)

                else -> {
                    val result = attempt { uploader.upload(pin, marks.newPinPending) } ?: RecoveryUploadResult.Failed
                    if (result is RecoveryUploadResult.Uploaded) record(token, result)
                    result
                }
            }
        }

    private suspend fun record(token: Long, result: RecoveryUploadResult.Uploaded) = marksLock.withLock {
        if (token != generation.get()) return@withLock
        val answer = RecoveryRegistration(clock.now(), result.attemptsLeft)
        val marks = if (result.stored) {
            RecoveryPinMarks(registered = answer)
        } else {
            // Only an unchanged PIN is answered so; a pending new PIN would have been sent as new.
            RecoveryPinMarks(keptOther = answer, newPinPending = attempt { flag.marks().newPinPending } == true)
        }
        attempt { flag.setMarks(marks) }
    }

    private fun isFresh(at: Instant): Boolean = clock.now() - at in Duration.ZERO..<REFRESH_AFTER

    @Suppress("TooGenericExceptionCaught", "SwallowedException") // Storage or network: silent, retried next time.
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        null
    }

    companion object {
        /** An answer older than this is asked for again at the next correct PIN (a server key may have rotated). */
        val REFRESH_AFTER: Duration = 30.days

        private const val ZERO_CHAR = '\u0000'
    }
}

/**
 * [RecoveryPinFlag] in app-private `SharedPreferences` (`sprout_recovery_pin`, also in the wipe's file list). Not
 * secret: times, counts and one boolean. Read and written on [io], with `commit` so a write is on disk when it returns.
 */
class SharedPreferencesRecoveryPinFlag(context: Context, private val io: CoroutineDispatcher) : RecoveryPinFlag {
    private val appContext = context.applicationContext
    private val preferences by lazy { appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE) }
    private val state = MutableStateFlow<RecoveryPinMarks?>(null)
    private val loading = Mutex()

    override val marks: Flow<RecoveryPinMarks> = flow {
        load()
        emitAll(state.filterNotNull())
    }

    override suspend fun marks(): RecoveryPinMarks = load()

    override suspend fun setMarks(marks: RecoveryPinMarks) {
        withContext(io) {
            preferences.edit(commit = true) {
                clear()
                marks.registered?.let { put(KEY_REGISTERED, it) }
                marks.keptOther?.let { put(KEY_KEPT_OTHER, it) }
                if (marks.newPinPending) putBoolean(KEY_NEW_PIN_PENDING, true)
            }
        }
        state.value = marks
    }

    override suspend fun clear() = setMarks(RecoveryPinMarks())

    private suspend fun load(): RecoveryPinMarks = state.value ?: loading.withLock {
        state.value ?: withContext(io) { read() }.also { state.value = it }
    }

    private fun read(): RecoveryPinMarks = RecoveryPinMarks(
        registered = registration(KEY_REGISTERED),
        keptOther = registration(KEY_KEPT_OTHER),
        newPinPending = preferences.getBoolean(KEY_NEW_PIN_PENDING, false),
    )

    private fun registration(key: String): RecoveryRegistration? {
        val at = preferences.getLong("$key.at", NONE).takeIf { it != NONE } ?: return null
        val left = preferences.getInt("$key.left", MAX_ATTEMPTS).coerceIn(0, MAX_ATTEMPTS)
        return RecoveryRegistration(Instant.fromEpochMilliseconds(at), left)
    }

    private fun android.content.SharedPreferences.Editor.put(key: String, registration: RecoveryRegistration) {
        putLong("$key.at", registration.at.toEpochMilliseconds())
        putInt("$key.left", registration.attemptsLeft)
    }

    companion object {
        /** The `SharedPreferences` file name (`shared_prefs/sprout_recovery_pin.xml`), for the wipe's file list. */
        const val FILE_NAME: String = "sprout_recovery_pin"
        private const val KEY_REGISTERED = "registered"
        private const val KEY_KEPT_OTHER = "kept_other"
        private const val KEY_NEW_PIN_PENDING = "new_pin_pending"
        private const val NONE = Long.MIN_VALUE
        private const val MAX_ATTEMPTS = 10
    }
}
