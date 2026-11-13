package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.crypto.LockoutDecision
import dev.kpetriv.sprout.core.crypto.ParentAuthAvailability
import dev.kpetriv.sprout.core.database.HouseholdPolicyLimits
import dev.kpetriv.sprout.core.domainledger.LedgerPolicy
import dev.kpetriv.sprout.core.domainledger.chat.MessageKind
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.ThreadId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Instant

/*
 * What settings needs from other features. Features never depend on each other (CLAUDE.md), so the app adapts these
 * to `feature/lock` (ParentSession, ParentKeyEnrolment, ParentKeyRenewal, LockSettings) and binds them in Hilt;
 * tests use fakes.
 */

/**
 * Changes the parent PIN inside an unlocked parent session. The app adapts `ParentSession.changePin`, which checks
 * the new PIN's rules and confirmation, verifies [current] under the PIN lockout and saves the new hash.
 * Implementations zero all three arrays before returning.
 */
fun interface PinChanger {
    suspend fun changePin(current: CharArray, new: CharArray, confirm: CharArray): PinChangeOutcome
}

/** Outcome of [PinChanger.changePin]; the app maps `feature/lock`'s `ChangePinResult` onto it. */
sealed interface PinChangeOutcome {
    data object Changed : PinChangeOutcome

    /** The current PIN was wrong; [next] is the lockout decision that now applies. */
    data class WrongCurrentPin(val next: LockoutDecision) : PinChangeOutcome

    /** Too many wrong PINs: wait, or unlock with the fingerprint ([LockoutDecision.RequireBiometric]). */
    data class LockedOut(val decision: LockoutDecision) : PinChangeOutcome

    /** The new PIN is too easy to guess or not six digits. */
    data class Invalid(val problem: NewPinProblem) : PinChangeOutcome

    /** The confirmation differs from the new PIN. */
    data object Mismatch : PinChangeOutcome

    /** The parent session is not unlocked. */
    data object NotAllowed : PinChangeOutcome

    /** Storage or Keystore failure; the old PIN still works. */
    data object Failed : PinChangeOutcome
}

/** Why a new PIN was refused (mirrors `feature/lock`'s `PinRuleViolation`). */
enum class NewPinProblem {
    WRONG_LENGTH,
    NOT_DIGITS,
    ALL_SAME,
    SEQUENCE,
}

/**
 * "Restore household settings" (D-REL-22, audit M1): a real household whose policy exists nowhere (its creating phone
 * was lost before the policy was pushed) gets it back from a parent phone without a strong biometric, which cannot
 * recover it through fingerprint signing. The app adapts `HouseholdPolicyRecovery`: [check] runs the same checks as the
 * recovery at key registration (one clean pull, nothing held, no policy document on the server, the household's own
 * currency), [restore] writes the policy with that currency and parent signatures off. Settings asks the parent to
 * confirm ([ParentConfirmation], the PIN on such a phone) between the two, as for the other policy writes (D-M7-17).
 */
interface HouseholdSettingsRestorer {
    /** True while this phone is an ACTIVE parent phone of a real household without its household policy row. */
    fun observeNeeded(): Flow<Boolean>

    /** Whether the policy can be restored now. May sync and read the server (bounded, about 20 s). */
    suspend fun check(): RestoreCheck

    /** Writes the policy [check] found restorable; true when this phone has the policy afterwards. */
    suspend fun restore(): Boolean
}

/** What [HouseholdSettingsRestorer.check] found. */
enum class RestoreCheck {
    /** The household has no policy anywhere and its currency is known: restore after the parent confirms. */
    READY,

    /** This phone has the policy (it arrived meanwhile): nothing to restore. */
    PRESENT,

    /** The policy may exist somewhere this phone has not applied yet, or the check could not complete: wait. */
    NOT_SYNCED,
}

/**
 * Fingerprint signing for this device (audit H1, H2, M1). The app adapts `feature/lock`'s PIN-gated
 * `ParentKeyEnrolment` and `ParentKeyRenewal`: each call needs the parent PIN, verified under the PIN lockout, then
 * creates the parent key, proves it with one fingerprint prompt, and registers it with the household policy (which
 * makes parent signatures required). Implementations zero [pin] before returning.
 */
interface ParentKeyRegistrar {
    /**
     * True when a parent key is registered but this device's key can no longer sign for it (a fingerprint was added
     * or removed, or the key is missing or different): the card offers renewal.
     */
    suspend fun needsRenewal(): Boolean

    /** Turns fingerprint signing on. Refuses ([SigningKeyOutcome.AlreadyOn]) when a key is registered already. */
    suspend fun enrol(pin: CharArray): SigningKeyOutcome

    /** Replaces the registered key after a fingerprint change. */
    suspend fun renew(pin: CharArray): SigningKeyOutcome
}

/** Outcome of [ParentKeyRegistrar.enrol] and [ParentKeyRegistrar.renew]; the app maps `feature/lock`'s result. */
sealed interface SigningKeyOutcome {
    /** The new key is registered: every parent money change from now on needs the fingerprint. */
    data object Registered : SigningKeyOutcome

    /** No usable strong biometric on this device (D-M2-1). */
    data class Unavailable(val availability: ParentAuthAvailability) : SigningKeyOutcome

    /** Enrolment refused: a key is already registered. */
    data object AlreadyOn : SigningKeyOutcome

    /** Renewal refused: no key is registered yet. */
    data object NotRegistered : SigningKeyOutcome

    /** Wrong PIN; [next] is the lockout decision that now applies. */
    data class WrongPin(val next: LockoutDecision) : SigningKeyOutcome

    /** The PIN was not checked because of the lockout. */
    data class LockedOut(val decision: LockoutDecision) : SigningKeyOutcome

    /** The fingerprint prompt was dismissed. */
    data object Cancelled : SigningKeyOutcome

    /**
     * Refused before the PIN check and the fingerprint prompt: this phone has not synced the household policy yet
     * (D-REL-15). Nothing changed.
     */
    data object PolicyNotSynced : SigningKeyOutcome

    /** The parent session is not unlocked, or storage, Keystore or the prompt failed. Nothing changed. */
    data object Failed : SigningKeyOutcome
}

/** The parent-lock preferences settings edits. The app adapts `feature/lock`'s `LockSettings`. */
interface LockPreferences {
    /** Lock the parent area as soon as the app leaves the foreground. */
    val lockWhenLeaving: StateFlow<Boolean>

    fun setLockWhenLeaving(enabled: Boolean)
}

/**
 * On-device AI settings (M6 §3.5). The app adapts `core/ai`'s `AiGateway.status`/`requestDownload` and `AiPreferences`.
 * AI is on by default where the device has AICore; the model is downloaded only after the parent agrees once here
 * (D-M6-6). Nothing in these settings sends anything off the phone.
 */
interface AiSettingsSource {
    /** The current status and preferences; emits again when either changes. */
    fun observe(): Flow<AiSettings>

    /** Turns the on-device model on or off; off means every AI feature uses the built-in rules. */
    suspend fun setEnabled(enabled: Boolean)

    /**
     * Records the parent's answer to "Download the model". True also asks AICore to download (the system decides
     * when, usually on Wi-Fi); false only withdraws the consent (a downloaded model is managed by the system).
     */
    suspend fun setDownloadConsent(consent: Boolean)
}

/** What [AiSettingsSource] reports. */
data class AiSettings(val status: AiModelStatus, val enabled: Boolean, val downloadConsent: Boolean)

/** Mirrors `core/ai`'s `AiAvailability`. */
enum class AiModelStatus {
    /** Gemini Nano is ready on this phone. */
    AVAILABLE,

    /** The device supports it; the model is not downloaded yet. */
    DOWNLOADABLE,

    /** The system is downloading the model (when it chooses, usually on Wi-Fi). */
    DOWNLOADING,

    /** No AICore on this device: the built-in rules do everything. */
    UNAVAILABLE,
}

/**
 * The synced household policy's chat retention and daily game cap (M7 §1, D-M7-10). The app adapts `core/database`'s
 * `HouseholdPolicyRepository` (parent-only writes that go to Room and the outbox like every policy change). Writes
 * throw on failure; the ViewModels turn any failure into a message. While this phone has no policy yet, [observe]
 * emits nothing and the writes throw `core/database`'s `NoHouseholdPolicyYetException` (D-M7-57): the screens say
 * they are waiting for the household policy to sync.
 */
interface HouseholdRules {
    /** The policy's values; emits again when another parent device changes them. */
    fun observe(): Flow<HouseholdRulesState>

    /** Keep chat messages for [days] (1 to [HouseholdRulesState.MAX_RETENTION_DAYS]); null keeps them forever. */
    suspend fun setChatRetentionDays(days: Int?)

    /**
     * The most games can earn per kid per day, in the household currency's minor units (0 to
     * [HouseholdRulesState.MAX_DAILY_GAME_CAP_MINOR]); null is the default
     * ([HouseholdRulesState.DEFAULT_DAILY_GAME_CAP_MINOR]).
     */
    suspend fun setDailyGameCap(minorUnits: Long?)
}

/** What [HouseholdRules] reports: null fields are "not set" (keep forever, the default cap). */
data class HouseholdRulesState(val chatRetentionDays: Int?, val dailyGameCapMinor: Long?) {
    /** The cap the ledger applies (ADR-0009): the policy's, or the default. */
    val effectiveDailyGameCapMinor: Long get() = dailyGameCapMinor ?: DEFAULT_DAILY_GAME_CAP_MINOR

    companion object {
        /** The ledger's default (ADR-0009). */
        val DEFAULT_DAILY_GAME_CAP_MINOR: Long = LedgerPolicy.DEFAULT_DAILY_GAME_CAP.minorUnits

        /** The policy's bounds (the rules and the server enforce the same). */
        val MAX_DAILY_GAME_CAP_MINOR: Long = HouseholdPolicyLimits.DAILY_GAME_CAP_MINOR.last
        val MAX_RETENTION_DAYS: Int = HouseholdPolicyLimits.CHAT_RETENTION_DAYS.last
    }
}

/**
 * Asks the parent to prove they are present again before a sensitive action (M7 §2, §3: the readable chat export,
 * "Delete chat history now" and the household policy writes, D-M7-17), on top of the unlocked parent area. The app
 * adapts `feature/lock`: the strong biometric prompt where the device has one, the parent PIN (under its lockout)
 * otherwise.
 */
fun interface ParentConfirmation {
    suspend fun confirm(purpose: ConfirmationPurpose): ConfirmationOutcome
}

/** Why [ParentConfirmation] is asked; the app words its prompt from it. */
enum class ConfirmationPurpose {
    /** The household export, which now includes every chat in plain text. */
    EXPORT_HOUSEHOLD,

    /** Deleting every chat message of the household. */
    DELETE_CHAT_HISTORY,

    /** Changing how long chat messages are kept (a shorter period deletes older ones everywhere). */
    CHANGE_CHAT_RETENTION,

    /** Changing the daily game limit, which re-decides what games earned (D-M7-11). */
    CHANGE_GAME_CAP,

    /** Restoring a household policy that exists nowhere, with parent signatures off (D-REL-22). */
    RESTORE_HOUSEHOLD_SETTINGS,
}

enum class ConfirmationOutcome {
    CONFIRMED,

    /** The parent dismissed the prompt: do nothing, say nothing. */
    CANCELLED,

    /** Locked out, no prompt host, or the prompt failed: nothing happened. */
    FAILED,
}

/**
 * "Delete chat history now" (D-M7-8, D-M7-58): the app deletes every local message, read mark and cached blob before
 * now, and this phone's own messages still waiting to upload (`core/database`'s purge use case), then asks the server
 * to delete them too (`pruneMessages` with the explicit instant). Other phones are not told: they keep their copies
 * until their own chat retention removes them (a synced purge is M8). Called only after the typed confirmation and
 * [ParentConfirmation].
 */
fun interface ChatHistoryPurger {
    suspend fun purgeAll(): ChatPurgeOutcome
}

enum class ChatPurgeOutcome {
    /**
     * Deleted on this phone and on the server. Other phones keep their copies until their chat retention removes
     * them; nothing tells them to delete now (D-M7-58).
     */
    PURGED,

    /** Deleted on this phone; the server could not be reached (the app retries the server prune later). */
    PURGED_ON_THIS_PHONE,

    /** Nothing was deleted. */
    FAILED,
}

/**
 * The readable chat for the household export (M7 §3, D-M7-9). The app adapts `core/database`'s `ChatExportReader`
 * (`threads`/`read` and `exportMedia`), which decodes this phone's stored messages and decrypts the cached media;
 * nothing here goes to the server. Personal data: never log it.
 */
interface ChatExportSource {
    /** Every thread with a visible message, each oldest message first. Throws when the chat cannot be read. */
    suspend fun threads(): List<ChatExportThread>

    /**
     * Writes [thread]'s photos and voice notes to [sink] under their [ChatExportMessage.media] names (fetching ones
     * that are not cached, best effort) and returns those it could not write.
     */
    suspend fun media(thread: ChatExportThread, sink: ChatMediaSink): List<ChatMissingMedia>
}

/** Receives one decrypted media file. The caller may wipe [bytes] once this returns: keep a copy. */
fun interface ChatMediaSink {
    suspend fun write(fileName: String, bytes: ByteArray)
}

/** A media file the export could not include; [reason] is the reader's (`NOT_AVAILABLE`, `LOCKED`, `INVALID`). */
data class ChatMissingMedia(val fileName: String, val reason: String)

/** One kid's thread ([threadId] for the adapter; the zip names the file after [kidId]). [toString] omits messages. */
class ChatExportThread(val threadId: ThreadId, val kidId: KidId, val messages: List<ChatExportMessage>) {
    override fun toString(): String = "ChatExportThread(threadId=$threadId, kidId=$kidId, messages=${messages.size})"
}

/**
 * One decoded message, as `core/database`'s `ExportedMessage`: [text] for a text message (or a photo's caption),
 * [presetId] and [emoji] for a preset, [durationMs] for a voice note, [media] the photo's or voice note's file name
 * (`<blobId>.jpg`, `<blobId>.m4a`). A [locked] message (no key on this phone yet) has no content. [toString] omits
 * the content.
 */
@Suppress("LongParameterList") // One field per exported fact.
class ChatExportMessage(
    val sentAt: Instant,
    val author: Role,
    val kind: MessageKind,
    val text: String? = null,
    val presetId: String? = null,
    val emoji: String? = null,
    val durationMs: Long? = null,
    val media: String? = null,
    val locked: Boolean = false,
) {
    override fun toString(): String = "ChatExportMessage(sentAt=$sentAt, author=$author, kind=$kind, locked=$locked)"
}

/**
 * Account recovery's PIN check value (ADR-0013 §1) for Settings → Security. The app adapts `feature/lock`'s
 * `RecoveryPinRegistration` (the registration time) and `ParentSession.registerRecoveryPin`.
 */
interface AccountRecoverySetup {
    /**
     * Null when account recovery does not apply here (the trial household, a phone that is not a signed-in parent's
     * ACTIVE device); otherwise whether a check value is registered and what is left of its wrong-PIN budget.
     */
    val state: Flow<RecoverySetupState?>

    /** Checks [pin] under the PIN lockout, then registers its check value. Zeroes [pin]. */
    suspend fun finish(pin: CharArray): RecoverySetupOutcome
}

/**
 * The account recovery row's facts: [ready] when the PIN's check value is registered; [keptOther] when the server kept
 * the value of the PIN set or changed last on another working phone of the account (this phone's PIN does not
 * restore); [attemptsLeft] (0..10, null when unknown) as the server last said: below 10, wrong PINs were tried in a
 * restore (ADR-0013 §1, §3).
 */
data class RecoverySetupState(val ready: Boolean, val attemptsLeft: Int? = null, val keptOther: Boolean = false)

/** Outcome of [AccountRecoverySetup.finish]. */
sealed interface RecoverySetupOutcome {
    data object Registered : RecoverySetupOutcome

    /** Wrong PIN; [next] is the lockout decision that now applies. */
    data class WrongPin(val next: LockoutDecision) : RecoverySetupOutcome

    data class LockedOut(val decision: LockoutDecision) : RecoverySetupOutcome

    /** The PIN was right but the server could not be reached: try again later. */
    data object Offline : RecoverySetupOutcome

    /** The PIN was right but the server refused the call: try again later. */
    data object Refused : RecoverySetupOutcome

    /** The PIN was right; the server kept the value of the PIN set or changed last on another working phone. */
    data object KeptOther : RecoverySetupOutcome

    /** Nothing to set up here any more (the session locked, or the phone is no longer a parent's ACTIVE device). */
    data object NotAvailable : RecoverySetupOutcome
}
