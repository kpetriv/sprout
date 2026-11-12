package dev.kpetriv.sprout.sync

import android.app.Application
import dev.kpetriv.sprout.core.database.BlobTarget
import dev.kpetriv.sprout.core.database.HouseholdFiatCurrencyProvider
import dev.kpetriv.sprout.core.database.LocaleFiatCurrencyProvider
import dev.kpetriv.sprout.core.database.TestHousehold
import dev.kpetriv.sprout.core.domainledger.AttestationLevel
import dev.kpetriv.sprout.core.domainledger.Currency
import dev.kpetriv.sprout.core.domainledger.DeviceOwner
import dev.kpetriv.sprout.core.domainledger.DeviceStatus
import dev.kpetriv.sprout.core.domainledger.RegisteredDevice
import dev.kpetriv.sprout.core.domainledger.chat.MessagePlaintext
import dev.kpetriv.sprout.core.model.HouseholdId
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.model.ThreadId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Switching accounts clears the M4 chat state in place too: messages, thread keys and the cached blobs (D-M3-47). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class LocalHouseholdRowsTest {
    private val kidId = KidId("kid-leo")
    private val household = TestHousehold(
        RuntimeEnvironment.getApplication(),
        sessionFor = TestHousehold.parentSession(HouseholdId("home-1"), "parent-uid"),
    )

    @After
    fun close() = household.close()

    @Test
    fun `messages, thread keys and blob files are gone after the clear`() = runTest {
        val self = RegisteredDevice(
            deviceId = household.deviceId,
            owner = DeviceOwner(household.session.uid, household.session.role, null),
            publicKey = household.keyStore.publicKey().encoded,
            status = DeviceStatus.Active,
            attestation = AttestationLevel.NONE,
            encryptionKey = household.boundEncryptionKey(),
        )
        household.applier.applyRegistry(listOf(self), emptyList())
        household.addKid(kidId.value)
        household.threadKeyMaintenance()
        household.confirmThreadKeys()
        val thread = ThreadId.of(kidId)
        household.messageWrites.send(thread, MessagePlaintext.Text("secret plans"))
        household.blobWrites.store(BlobTarget.Thread(thread), byteArrayOf(1, 2, 3))
        assertTrue(household.blobDirectory.listFiles().orEmpty().isNotEmpty())
        var secretsCleared = 0

        household.policies.ensure(household.gbp)
        val currencies =
            HouseholdFiatCurrencyProvider(household.policies, LocaleFiatCurrencyProvider { Locale.GERMANY })
        assertEquals(household.gbp, currencies.currentFiatCurrency())

        val secrets = ParentSecrets { secretsCleared += 1 }
        LocalHouseholdRows(household.database, household.files, secrets, Dispatchers.IO, currencies).clear()

        assertEquals(Currency.Fiat("EUR"), currencies.fiatCurrency(), "the previous household's currency is forgotten")

        assertTrue(household.database.messageDao().all().isEmpty())
        assertTrue(household.database.blobDao().all().isEmpty())
        assertTrue(household.database.threadKeyDao().all().isEmpty())
        assertTrue(household.blobDirectory.listFiles().orEmpty().isEmpty())
        assertEquals(1, secretsCleared)
    }
}
