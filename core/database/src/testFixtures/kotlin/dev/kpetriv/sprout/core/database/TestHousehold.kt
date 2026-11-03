package dev.kpetriv.sprout.core.database

import android.content.Context
import androidx.room.Room
import dev.kpetriv.sprout.core.crypto.AesGcmAead
import dev.kpetriv.sprout.core.crypto.DeviceKeyStore
import dev.kpetriv.sprout.core.crypto.DeviceSigner
import dev.kpetriv.sprout.core.crypto.EncryptionKeyStore
import dev.kpetriv.sprout.core.crypto.FakeParentAuthorizer
import dev.kpetriv.sprout.core.crypto.HouseholdKeyDerivation
import dev.kpetriv.sprout.core.crypto.InMemoryBlobStorage
import dev.kpetriv.sprout.core.crypto.InMemoryDeviceKeyStore
import dev.kpetriv.sprout.core.crypto.InMemoryKeyWrapper
import dev.kpetriv.sprout.core.crypto.JceEcdsaVerifier
import dev.kpetriv.sprout.core.crypto.JceX25519KeyAgreement
import dev.kpetriv.sprout.core.crypto.ParentSigner
import dev.kpetriv.sprout.core.crypto.SecretStoreEncryptionKeyStore
import dev.kpetriv.sprout.core.crypto.WrappedSecretStore
import dev.kpetriv.sprout.core.crypto.chat.BlobCipher
import dev.kpetriv.sprout.core.crypto.chat.MessageCipher
import dev.kpetriv.sprout.core.crypto.chat.ThreadKeyring
import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.database.usecase.ApproveSubmissionUseCase
import dev.kpetriv.sprout.core.database.usecase.DrainPendingParentActionsUseCase
import dev.kpetriv.sprout.core.database.usecase.DrainResult
import dev.kpetriv.sprout.core.database.usecase.PendingPhotoUploads
import dev.kpetriv.sprout.core.database.usecase.PlanScheduledActionsUseCase
import dev.kpetriv.sprout.core.database.usecase.PurchaseRewardUseCase
import dev.kpetriv.sprout.core.database.usecase.PurgeLocalHouseholdUseCase
import dev.kpetriv.sprout.core.database.usecase.ReconcileTimeBankUseCase
import dev.kpetriv.sprout.core.database.usecase.RecordManualAdjustmentUseCase
import dev.kpetriv.sprout.core.database.usecase.RedeemOwedGrantsUseCase
import dev.kpetriv.sprout.core.database.usecase.RegisterParentKeyUseCase
import dev.kpetriv.sprout.core.database.usecase.StartTimeBankUseCase
import dev.kpetriv.sprout.core.database.usecase.StopTimeBankUseCase
import dev.kpetriv.sprout.core.database.usecase.SweepOrphanPhotosUseCase
import dev.kpetriv.sprout.core.database.usecase.ThreadKeyMaintenance
import dev.kpetriv.sprout.core.database.usecase.TimeBankStatusReader
import dev.kpetriv.sprout.core.database.usecase.UploadChorePhotosUseCase
import dev.kpetriv.sprout.core.domainhousehold.PendingParentAction
import dev.kpetriv.sprout.core.domainledger.Account
import dev.kpetriv.sprout.core.domainledger.AccountKind
import dev.kpetriv.sprout.core.domainledger.BoundEncryptionKey
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.EncryptionKeyBindingPayload
import dev.kpetriv.sprout.core.domainledger.EventAuthor
import dev.kpetriv.sprout.core.domainledger.ParentAuthorization
import dev.kpetriv.sprout.core.domainledger.toHex
import dev.kpetriv.sprout.core.model.DeviceId
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.Kid
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.KidUiTier
import dev.kpetriv.sprout.core.model.Role
import dev.kpetriv.sprout.core.model.UserId
import dev.kpetriv.sprout.core.model.YearMonth
import dev.kpetriv.sprout.core.time.Clock
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.SecureRandom
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Instant

/**
 * The data layer of one device wired as production wires it (device-key event check, `household_policy`, the
 * registry-checked reward catalogue, the session-backed author, the sync outbox and applier), on an in-memory
 * database, with in-memory crypto: [keyStore] for the device key (a fresh software key by default) and
 * [FakeParentAuthorizer] for the parent key.
 *
 * [session] is stored before anything else (the trial session of this device's key by default, see
 * [Session.localTrial]); pass `sessionFor` to sign the household in as a real PARENT or KID session. Two instances
 * with distinct key stores and sessions of one household, wired to `SyncEngine`s over one `FakeBackend`, are two
 * devices of that household.
 */
@Suppress("LongParameterList", "TooManyFunctions") // One field per repository and use case of the data layer.
class TestHousehold(
    context: Context,
    val clock: FakeClock = FakeClock(DEFAULT_START),
    val keyStore: DeviceKeyStore = InMemoryDeviceKeyStore(clock),
    sessionFor: (DeviceId) -> Session = { Session.localTrial(it, clock.now()) },
) : AutoCloseable {
    val database: SproutDatabase = inMemoryDatabase(context)
    val gbp = Currency.Fiat("GBP")
    val signer = DeviceSigner(keyStore, Dispatchers.Unconfined)

    /** This device's id: the fingerprint of [keyStore]'s key. */
    val deviceId: DeviceId = DeviceId(keyStore.publicKey().fingerprint().toHex())
    val session: Session = sessionFor(deviceId)
    val sessions = RoomSessionStore(database, clock)
    val authors: SessionAuthorProvider = SessionBackedAuthorProvider(sessions)
    val signatureCheck = VerifierSignatureCheck(JceEcdsaVerifier())
    val registry = RoomDeviceRegistry(database)
    val ledger = RoomLedgerRepository(database, clock, signer, JceEcdsaVerifier())
    val writer = RoomLedgerWriter(database, ledger, signer, authors, clock)
    val outbox = RoomOutboxSource(database, clock)
    private val random = SecureRandom()
    private val aead = AesGcmAead(random)
    private val secretStore =
        WrappedSecretStore(InMemoryKeyWrapper(), InMemoryBlobStorage(), random, Dispatchers.Unconfined)

    /** This device's X25519 chat key (`chat.x25519.v1`), in memory. */
    val encryptionKeys: EncryptionKeyStore = SecretStoreEncryptionKeyStore(secretStore, JceX25519KeyAgreement(random))
    val chatCrypto = ChatCrypto(
        ThreadKeyring(JceX25519KeyAgreement(random), HouseholdKeyDerivation(), aead, random),
        MessageCipher(aead),
        BlobCipher(aead),
        encryptionKeys,
    )
    val applier = RoomRemoteApplier(database, ledger, clock, signatureCheck, chatCrypto)

    val photoDirectory = newPhotoDirectory(context)
    val blobDirectory = File(context.noBackupFilesDir, "blobs-${UUID.randomUUID()}")
    val files = LocalDataFiles(
        databaseFile = File(context.cacheDir, "unused-${UUID.randomUUID()}.db"),
        photoDirectory = photoDirectory,
        secretsDirectory = File(context.cacheDir, "secrets-${UUID.randomUUID()}"),
        blobDirectory = blobDirectory,
    )
    val photos: PhotoStore = encryptedPhotoStore(database, authors, clock, photoDirectory)
    val blobCache = BlobCache(database, blobDirectory, aead, secretStore, clock, Dispatchers.Unconfined)

    val kids = RoomKidRepository(database, authors, clock, photos, signer)
    val accounts = RoomAccountRepository(database, authors, clock, signer)
    val chores = RoomChoreRepository(database, authors, clock, ledger, photos, signer)
    val rewards = RoomRewardRepository(database, authors, clock, signer)
    val allowances = RoomAllowanceRepository(database, authors, clock, signer)
    val goals = RoomSavingsGoalRepository(database, authors, clock, signer)
    val interest = RoomInterestPolicyRepository(database, authors, clock, signer)
    val policies = RoomHouseholdPolicyRepository(database, authors, { gbp }, clock, signer)
    val pending = RoomPendingParentActionRepository(database, authors, clock)

    val parentAuthorizer = FakeParentAuthorizer()
    private val parentAuthorizations = ParentAuthorizations(ParentSigner())

    val registerKey = RegisterParentKeyUseCase(policies)
    val approve = ApproveSubmissionUseCase(chores, accounts, ledger, writer, policies, clock)
    val purchase = PurchaseRewardUseCase(rewards, accounts, writer)
    val redeem = RedeemOwedGrantsUseCase(ledger, accounts, writer)
    val adjust = RecordManualAdjustmentUseCase(accounts, writer, policies)
    val describer = EnglishPendingActionDescriber(kids)
    val drainUseCase = DrainPendingParentActionsUseCase(pending, ledger, writer, policies, allowances, kids, describer)
    val plan = PlanScheduledActionsUseCase(
        kids,
        accounts,
        allowances,
        interest,
        pending,
        ledger,
        redeem,
        HouseholdCalendar(clock) { ZoneOffset.UTC },
    )
    val purge = PurgeLocalHouseholdUseCase(database, files, clock, Dispatchers.Unconfined)

    val messageWrites = MessageWrites(database, clock, signer, authors, chatCrypto, signatureCheck)
    val blobWrites = BlobWrites(database, clock, signer, authors, chatCrypto, signatureCheck, blobCache)
    val chatBlobs = ChatBlobs(database, blobCache, chatCrypto, signatureCheck)
    val threadKeyWrites = ThreadKeyWrites(database, clock, signer, authors, chatCrypto)
    val threadKeyMaintenance = ThreadKeyMaintenance(database, sessions, chatCrypto, signatureCheck, threadKeyWrites)
    val messageReader = MessageReader(database)
    val chatThreads = ChatThreads(database)
    val threadKeyStatus = ThreadKeyStatusReader(database, signatureCheck)

    val timeBankStatus = TimeBankStatusReader(ledger, accounts)
    val startTimeBank = StartTimeBankUseCase(timeBankStatus, writer)
    val stopTimeBank = StopTimeBankUseCase(timeBankStatus, writer, clock)
    val reconcileTimeBank = ReconcileTimeBankUseCase(ledger, timeBankStatus, writer, sessions, clock)

    val photoUploads = PendingPhotoUploads(database)
    val uploadChorePhotos = UploadChorePhotosUseCase(photoUploads, photos, blobWrites)
    val sweepOrphanPhotos = SweepOrphanPhotosUseCase(database, photos, clock)

    init {
        runBlocking { sessions.save(session) }
    }

    /** The author of this device's own writes. */
    val author: EventAuthor get() = session.author()

    /** Drains the queue; fails the test if another drain was running. */
    suspend fun drain(provider: suspend (PendingParentAction) -> ParentAuthorization): DrainResult.Drained =
        drainUseCase(provider) as DrainResult.Drained

    /**
     * What `feature/lock`'s PIN-gated enrolment does once the PIN is verified: creates the parent key and registers
     * it. Returns the policy with parent signatures required.
     */
    suspend fun registerParentKey(): HouseholdPolicy = registerKey.register(parentAuthorizer.createParentKey().encoded)

    /** One parent prompt: a fresh single-use authorisation from the parent key (create it first). */
    fun authorization(): ParentAuthorization = parentAuthorizations.from(parentAuthorizer.initSignature())

    /** A kid of this household with default GBP accounts. */
    suspend fun addKid(id: String = "kid-leo", age: Int = 9): Pair<Kid, List<Account>> {
        val kid = Kid(
            KidId(id),
            session.householdId,
            "Leo",
            "avatar-1",
            YearMonth(BIRTH_REFERENCE_YEAR - age, BIRTH_MONTH),
            KidUiTier.suggestedFor(age),
        )
        kids.upsert(kid)
        return kid to accounts.ensureDefaultAccounts(kid.id, gbp)
    }

    fun List<Account>.money(): Account = first { it.kind == AccountKind.MAIN && it.currency == gbp }

    fun List<Account>.savings(): Account = first { it.kind == AccountKind.SAVINGS }

    fun List<Account>.minutes(): Account = first { it.currency == Currency.Minutes }

    /**
     * This device's registered encryption key as the server would list it (M4 §1.1): its X25519 public key, bound to
     * this device by its device key.
     */
    suspend fun boundEncryptionKey(): BoundEncryptionKey {
        val publicKey = encryptionKeys.publicKey().bytes
        val signature = signer.sign(EncryptionKeyBindingPayload.of(session.householdId, deviceId, publicKey))
        return BoundEncryptionKey(session.householdId, publicKey, signature)
    }

    override fun close() {
        database.close()
        photoDirectory.deleteRecursively()
        blobDirectory.deleteRecursively()
    }

    companion object {
        /** The domain fixtures' start time. */
        val DEFAULT_START: Instant = Instant.parse("2026-11-02T08:00:00Z")
        private const val BIRTH_REFERENCE_YEAR = 2026
        private const val BIRTH_MONTH = 11

        /** A PARENT session of [householdId] for [uid] on the device with id `it`. */
        fun parentSession(householdId: HouseholdId, uid: String, at: Instant = DEFAULT_START) = { deviceId: DeviceId ->
            Session(UserId(uid), householdId, Role.PARENT, null, deviceId, RegistrationStatus.ACTIVE, at)
        }

        /** A KID session of [householdId] for [uid], linked to [kidId], on the device with id `it`. */
        fun kidSession(householdId: HouseholdId, uid: String, kidId: KidId, at: Instant = DEFAULT_START) =
            { deviceId: DeviceId ->
                Session(UserId(uid), householdId, Role.KID, kidId, deviceId, RegistrationStatus.ACTIVE, at)
            }
    }
}

/** An unencrypted in-memory database on the platform SQLite (no SQLCipher native library on the JVM). */
fun inMemoryDatabase(context: Context): SproutDatabase =
    Room.inMemoryDatabaseBuilder(context, SproutDatabase::class.java).build()

/** A fresh, not yet created directory under `noBackupFilesDir` for a test's photos. */
fun newPhotoDirectory(context: Context): File = File(context.noBackupFilesDir, "photos-${UUID.randomUUID()}")

/** An [EncryptedPhotoStore] with in-memory key storage, writing to [directory]. */
fun encryptedPhotoStore(
    database: SproutDatabase,
    authors: SessionAuthorProvider,
    clock: Clock,
    directory: File,
): EncryptedPhotoStore = EncryptedPhotoStore(
    database,
    directory,
    AesGcmAead(SecureRandom()),
    WrappedSecretStore(InMemoryKeyWrapper(), InMemoryBlobStorage(), SecureRandom(), Dispatchers.Unconfined),
    authors,
    clock,
    Dispatchers.Unconfined,
)

/**
 * A [SessionAuthorProvider] for repositories built without a session table: the trial parent of the local
 * household on the device whose key [signer] holds (what `LocalOnlyAuthorProvider` was before M3).
 */
class TrialAuthorProvider(private val signer: DeviceSigner) : SessionAuthorProvider {
    override suspend fun currentAuthor(): EventAuthor =
        EventAuthor(LocalTrial.HOUSEHOLD, LocalTrial.PARENT, Role.PARENT, signer.deviceId())
}
