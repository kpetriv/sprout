package dev.kpetriv.sprout.feature.settings

import dev.kpetriv.sprout.core.database.entity.RegistrationStatus
import dev.kpetriv.sprout.core.model.KidId
import dev.kpetriv.sprout.core.time.FakeClock
import dev.kpetriv.sprout.core.time.HouseholdCalendar
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.ZoneOffset
import java.util.zip.ZipInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** A context for events that carry one; the fakes never use it. */
private val RuntimeContext: android.content.Context = android.content.ContextWrapper(null)

/** Keeps a WhileSubscribed state flow hot for the test, collecting eagerly. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun <T> TestScope.hot(flow: StateFlow<T>): StateFlow<T> = flow.also {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { it.collect {} }
}

class DevicesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `lists PENDING first with fingerprints and attestation labels, then ACTIVE, then REVOKED`() = runTest {
        val state = hot(DevicesViewModel(FakeDeviceAdmin()).state)
        val rows = state.value.devices
        assertEquals(listOf(SECOND_PHONE, THIS_PHONE, LEO_PHONE, OLD_PHONE), rows.map { it.deviceId })
        assertEquals("3fa9 c01d 77e2 b4a8", rows[0].fingerprint)
        assertEquals(
            listOf(AttestationLabel.UNVERIFIED, AttestationLabel.STRONGBOX, AttestationLabel.TEE, AttestationLabel.TEE),
            rows.map { it.attestation },
        )
        assertTrue(rows[0].canApprove)
        assertFalse(rows[1].canRevoke, "this phone cannot revoke itself")
        assertFalse(rows[3].canRevoke, "a revoked phone cannot be revoked again")
        assertFalse(rows[2].canApprove)
    }

    @Test
    fun `approving shows the fingerprint first, then approves and reports it`() = runTest {
        val admin = FakeDeviceAdmin()
        val viewModel = DevicesViewModel(admin)
        val state = hot(viewModel.state)
        viewModel.onEvent(DevicesEvent.Review(SECOND_PHONE))
        assertEquals("3fa9 c01d 77e2 b4a8", state.value.reviewing?.fingerprint)
        assertEquals(emptyList(), admin.approved, "nothing is approved before the parent compared the codes")
        viewModel.onEvent(DevicesEvent.Approve)
        assertEquals(listOf(SECOND_PHONE), admin.approved)
        assertNull(state.value.reviewing)
        assertEquals(DevicesMessage.APPROVED, state.value.message)
    }

    @Test
    fun `a review asked for before the list loads opens once the device shows up`() = runTest {
        val admin = FakeDeviceAdmin(initial = null)
        val viewModel = DevicesViewModel(admin)
        val state = hot(viewModel.state)
        viewModel.onEvent(DevicesEvent.Review(SECOND_PHONE))
        assertNull(state.value.reviewing)
        admin.devices.value = householdDevices()
        assertEquals(SECOND_PHONE, state.value.reviewing?.deviceId)
    }

    @Test
    fun `only a PENDING parent phone can be reviewed`() = runTest {
        val viewModel = DevicesViewModel(FakeDeviceAdmin())
        val state = hot(viewModel.state)
        viewModel.onEvent(DevicesEvent.Review(LEO_PHONE))
        assertNull(state.value.reviewing)
    }

    @Test
    fun `it doesn't match closes the approval and offers to remove that phone instead`() = runTest {
        val admin = FakeDeviceAdmin()
        val viewModel = DevicesViewModel(admin)
        val state = hot(viewModel.state)
        viewModel.onEvent(DevicesEvent.Review(SECOND_PHONE))
        viewModel.onEvent(DevicesEvent.RejectReview)
        assertNull(state.value.reviewing)
        assertEquals(SECOND_PHONE, state.value.revoking?.deviceId)
        assertEquals(emptyList(), admin.approved)
        viewModel.onEvent(DevicesEvent.ConfirmRevoke)
        assertEquals(listOf(SECOND_PHONE), admin.revoked)
    }

    @Test
    fun `revoking asks first and refuses this phone`() = runTest {
        val admin = FakeDeviceAdmin()
        val viewModel = DevicesViewModel(admin)
        val state = hot(viewModel.state)
        viewModel.onEvent(DevicesEvent.AskRevoke(THIS_PHONE))
        assertNull(state.value.revoking)
        viewModel.onEvent(DevicesEvent.AskRevoke(LEO_PHONE))
        assertEquals(LEO_PHONE, state.value.revoking?.deviceId)
        assertEquals(emptyList(), admin.revoked)
        viewModel.onEvent(DevicesEvent.ConfirmRevoke)
        assertEquals(listOf(LEO_PHONE), admin.revoked)
        assertEquals(DevicesMessage.REVOKED, state.value.message)
    }

    @Test
    fun `a failed approval says why`() = runTest {
        val admin = FakeDeviceAdmin().apply { result = Result.failure(AdminException(AdminFailure.NETWORK)) }
        val viewModel = DevicesViewModel(admin)
        val state = hot(viewModel.state)
        viewModel.onEvent(DevicesEvent.Review(SECOND_PHONE))
        viewModel.onEvent(DevicesEvent.Approve)
        assertEquals(DevicesMessage.NETWORK, state.value.message)
        viewModel.onEvent(DevicesEvent.MessageShown)
        assertNull(state.value.message)
        assertEquals(RegistrationStatus.PENDING, state.value.devices.first().status)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LinkDeviceViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val clock = FakeClock(Instant.fromEpochSeconds(1_000_000))
    private val linking = FakeKidLinking(expiresAt = clock.now() + 10.minutes)
    private val leo = KidId("kid-leo")

    @Test
    fun `shows the code in two groups of four with a 10-minute countdown that runs out`() = runTest {
        val viewModel = LinkDeviceViewModel(linking, clock)
        viewModel.onEvent(LinkDeviceEvent.Start(leo, "Leo"))
        val state = viewModel.state
        assertEquals("K7QM 2X9P", state.value.groupedCode)
        assertEquals(600, state.value.remainingSeconds)
        assertEquals(listOf(leo), linking.requests)

        clock.advanceBy(1.seconds)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(599, state.value.remainingSeconds)
        assertEquals("9:59", countdown(state.value.remainingSeconds))

        clock.advanceBy(599.seconds)
        advanceTimeBy(599_000)
        runCurrent()
        assertTrue(state.value.expired)

        linking.expiresAt = clock.now() + 10.minutes
        viewModel.onEvent(LinkDeviceEvent.NewCode)
        assertEquals(listOf(leo, leo), linking.requests)
        assertEquals("ZZZZ 2222", state.value.groupedCode)
        assertFalse(state.value.expired)
        viewModel.onEvent(LinkDeviceEvent.Start(leo, "Leo"))
        assertEquals(2, linking.requests.size, "the same kid keeps its code")
        clock.advanceBy(10.minutes)
        advanceTimeBy(600_000)
        runCurrent()
    }

    @Test
    fun `the code never appears in toString`() {
        val viewModel = LinkDeviceViewModel(linking, clock)
        viewModel.onEvent(LinkDeviceEvent.Start(leo, "Leo"))
        assertFalse("K7QM" in viewModel.state.value.toString())
        assertFalse("K7QM" in IssuedLinkCode("K7QM2X9P", clock.now()).toString())
    }

    @Test
    fun `too many codes says so and offers a new one`() {
        linking.failure = AdminFailure.RATE_LIMITED
        val viewModel = LinkDeviceViewModel(linking, clock)
        viewModel.onEvent(LinkDeviceEvent.Start(leo, "Leo"))
        assertEquals(AdminFailure.RATE_LIMITED, viewModel.state.value.failure)
        assertNull(viewModel.state.value.code)
        linking.failure = null
        viewModel.onEvent(LinkDeviceEvent.NewCode)
        assertNotNull(viewModel.state.value.code)
        assertNull(viewModel.state.value.failure)
    }
}

class AccountViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @Test
    fun `sign out asks first, then signs out`() = runTest {
        val account = FakeAccountControl()
        val viewModel = AccountViewModel(account)
        val state = hot(viewModel.state)
        assertEquals("hh7f3k2q9xabc", state.value.householdId?.value)
        viewModel.onEvent(AccountEvent.AskSignOut)
        assertTrue(state.value.confirmingSignOut)
        assertEquals(0, account.signOuts)
        viewModel.onEvent(AccountEvent.ConfirmSignOut)
        assertEquals(1, account.signOuts)
        assertFalse(state.value.confirmingSignOut)
    }

    @Test
    fun `the hub shows the household sections only for a synced household`() = runTest {
        val account = FakeAccountControl()
        val synced = hot(SettingsHubViewModel(account).synced)
        assertTrue(synced.value)
        account.info.value = AccountInfo(email = null, householdId = null, trial = true)
        assertFalse(synced.value)
    }
}

class DataViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val calendar = HouseholdCalendar(FakeClock(Instant.parse("2026-11-03T10:00:00Z"))) { ZoneOffset.UTC }

    private fun TestScope.dataViewModel(data: FakeHouseholdData) =
        DataViewModel(data, FakeExportWriter(), calendar, FakeAccountControl(), UnconfinedTestDispatcher(testScheduler))
    private val bundle = Json.parseToJsonElement(
        """
        {"exportedAt":"2026-11-03T10:00:00Z","household":{"id":"hh7f3k2q9xabc","fiatCurrency":"EUR"},
         "events":[
           {"eventId":"e2","lamport":7,"createdAt":1793700000000,"type":"reward_purchased","kidId":"kid-leo",
            "accountId":"kid-leo.EUR.MAIN","amount":150,"currency":"EUR","hash":"h2"},
           {"eventId":"e1","lamport":3,"createdAt":1793600000000,"type":"chore_rewarded","kidId":"kid-leo",
            "accountId":"kid-leo.EUR.MAIN","amount":250,"currency":"EUR","hash":"h1"},
           {"eventId":"e3","lamport":9,"createdAt":1793700000500,"type":"manual_adjustment","kidId":"kid-leo",
            "accountId":"kid-leo.minutes.MAIN","amount":-15,"currency":"minutes","reason":"=cmd, \"x\""}
         ]}
        """,
    ) as JsonObject

    @Test
    fun `the ledger csv has one row per event in lamport order with the fixed columns`() {
        val csv = HouseholdExport.ledgerCsv(bundle)
        assertEquals(
            listOf(
                "eventId,lamport,createdAt,type,kidId,accountId,amount,currency",
                "e1,3,2026-11-02T06:13:20Z,chore_rewarded,kid-leo,kid-leo.EUR.MAIN,250,EUR",
                "e2,7,2026-11-03T10:00:00Z,reward_purchased,kid-leo,kid-leo.EUR.MAIN,150,EUR",
                "e3,9,2026-11-03T10:00:00.500Z,manual_adjustment,kid-leo,kid-leo.minutes.MAIN,-15,minutes",
            ),
            csv.trimEnd().split("\r\n"),
        )
    }

    @Test
    fun `cells are quoted and formula-looking text is defused`() {
        val tricky = Json.parseToJsonElement(
            """{"events":[{"eventId":"=HYPERLINK(1)","lamport":1,"type":"a,b","kidId":"say \"hi\""}]}""",
        ) as JsonObject
        val row = HouseholdExport.ledgerCsv(tricky).split("\r\n")[1]
        assertEquals("'=HYPERLINK(1),1,,\"a,b\",\"say \"\"hi\"\"\",,,", row)
    }

    @Test
    fun `export offers one zip with the json and the csv, and writes it where the parent picked`() = runTest {
        val writer = FakeExportWriter()
        val viewModel = DataViewModel(
            FakeHouseholdData(Result.success(bundle)),
            writer,
            calendar,
            FakeAccountControl(),
            UnconfinedTestDispatcher(testScheduler),
        )
        viewModel.onEvent(DataEvent.Export)
        val pending = assertNotNull(viewModel.state.value.pendingExport)
        assertEquals("sprout-export-2026-11-03.zip", pending.fileName)
        val entries = unzip(pending.bytes)
        assertEquals(setOf("household.json", "ledger.csv"), entries.keys)
        assertEquals(bundle, Json.parseToJsonElement(entries.getValue("household.json").decodeToString()))
        assertEquals(HouseholdExport.ledgerCsv(bundle), entries.getValue("ledger.csv").decodeToString())

        viewModel.onEvent(DataEvent.ExportDestination("content://picked/doc"))
        assertContentEquals(pending.bytes, writer.written["content://picked/doc"])
        assertNull(viewModel.state.value.pendingExport)
        assertEquals(DataMessage.EXPORTED, viewModel.state.value.message)
    }

    @Test
    fun `a cancelled picker drops the export, and a too-large household says so`() = runTest {
        val data = FakeHouseholdData(Result.success(bundle))
        val viewModel = dataViewModel(data)
        viewModel.onEvent(DataEvent.Export)
        viewModel.onEvent(DataEvent.ExportDestination(null))
        assertNull(viewModel.state.value.pendingExport)
        assertNull(viewModel.state.value.message)
        data.bundle = Result.failure(AdminException(AdminFailure.TOO_LARGE))
        viewModel.onEvent(DataEvent.Export)
        assertEquals(DataMessage.EXPORT_TOO_LARGE, viewModel.state.value.message)
    }

    @Test
    fun `deleting needs the household id's first 8 characters typed, then deletes and erases this phone`() = runTest {
        val data = FakeHouseholdData(Result.success(bundle))
        val viewModel = dataViewModel(data)
        viewModel.onEvent(DataEvent.AskDelete)
        assertEquals("hh7f3k2q", viewModel.state.value.deleteConfirmation)
        viewModel.onEvent(DataEvent.DeleteTyped("hh7f3k2"))
        assertFalse(viewModel.state.value.canConfirmDelete)
        viewModel.onEvent(DataEvent.ConfirmDelete)
        assertEquals(0, data.deletes)
        viewModel.onEvent(DataEvent.DeleteTyped("HH7F3K2Q"))
        assertFalse(viewModel.state.value.canConfirmDelete, "the confirmation is case-sensitive")
        viewModel.onEvent(DataEvent.DeleteTyped("hh7f3k2q"))
        assertTrue(viewModel.state.value.canConfirmDelete)
        viewModel.onEvent(DataEvent.ConfirmDelete)
        assertEquals(1, data.deletes)
        assertEquals(1, data.erased)
    }

    @Test
    fun `a failed deletion erases nothing`() = runTest {
        val data = FakeHouseholdData(Result.success(bundle)).apply {
            deleteResult = Result.failure(AdminException(AdminFailure.NETWORK))
        }
        val viewModel = dataViewModel(data)
        viewModel.onEvent(DataEvent.AskDelete)
        viewModel.onEvent(DataEvent.DeleteTyped("hh7f3k2q"))
        viewModel.onEvent(DataEvent.ConfirmDelete)
        assertEquals(0, data.erased)
        assertEquals(DataMessage.NETWORK, viewModel.state.value.message)
        assertNull(viewModel.state.value.deleteTyped)
    }

    @Test
    fun `a deletion that needs a recent sign-in signs in again and retries once`() = runTest {
        val data = FakeHouseholdData(Result.success(bundle)).apply {
            deleteResults += Result.failure(AdminException(AdminFailure.REAUTH_REQUIRED))
        }
        val viewModel = dataViewModel(data)
        viewModel.onEvent(DataEvent.AskDelete)
        viewModel.onEvent(DataEvent.DeleteTyped("hh7f3k2q"))
        viewModel.onEvent(DataEvent.ConfirmDelete)
        assertEquals(ReauthUi(usesPassword = true), viewModel.state.value.reauth)
        assertNull(viewModel.state.value.deleteTyped)
        assertEquals(0, data.erased)

        viewModel.onEvent(DataEvent.SignInAgain(RuntimeContext, "secret1"))

        assertEquals(listOf<String?>("secret1"), data.passwords)
        assertEquals(2, data.deletes)
        assertEquals(1, data.erased)
    }

    @Test
    fun `another account on the confirmation sign-in deletes nothing and says so`() = runTest {
        val data = FakeHouseholdData(Result.success(bundle)).apply {
            deleteResults += Result.failure(AdminException(AdminFailure.REAUTH_REQUIRED))
            signInResult = Result.failure(AdminException(AdminFailure.WRONG_ACCOUNT))
        }
        val viewModel = dataViewModel(data)
        viewModel.onEvent(DataEvent.AskDelete)
        viewModel.onEvent(DataEvent.DeleteTyped("hh7f3k2q"))
        viewModel.onEvent(DataEvent.ConfirmDelete)
        viewModel.onEvent(DataEvent.SignInAgain(RuntimeContext, "secret1"))
        assertEquals(ReauthProblem.WRONG_ACCOUNT, viewModel.state.value.reauth?.problem)
        assertEquals(1, data.deletes)
        assertEquals(0, data.erased)
        viewModel.onEvent(DataEvent.DismissReauth)
        assertNull(viewModel.state.value.reauth)
    }

    @Test
    fun `the retried deletion is tried only once`() = runTest {
        val data = FakeHouseholdData(Result.success(bundle)).apply {
            deleteResults += Result.failure(AdminException(AdminFailure.REAUTH_REQUIRED))
            deleteResults += Result.failure(AdminException(AdminFailure.REAUTH_REQUIRED))
        }
        val viewModel = dataViewModel(data)
        viewModel.onEvent(DataEvent.AskDelete)
        viewModel.onEvent(DataEvent.DeleteTyped("hh7f3k2q"))
        viewModel.onEvent(DataEvent.ConfirmDelete)
        viewModel.onEvent(DataEvent.SignInAgain(RuntimeContext, "secret1"))
        assertEquals(2, data.deletes)
        assertNull(viewModel.state.value.reauth)
        assertEquals(DataMessage.DELETE_FAILED, viewModel.state.value.message)
        assertEquals(0, data.erased)
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }
}
