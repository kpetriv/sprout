package dev.kpetriv.sprout.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeAiSettingsSource(
    initial: AiSettings = AiSettings(AiModelStatus.DOWNLOADABLE, enabled = true, downloadConsent = false),
) : AiSettingsSource {
    val settings = MutableStateFlow(initial)
    var fails = false
    var writeFails = false

    /** When set, writes suspend until it completes. */
    var gate: CompletableDeferred<Unit>? = null
    val consents = mutableListOf<Boolean>()

    override fun observe(): Flow<AiSettings> = if (fails) flow { error("preferences unreadable") } else settings

    override suspend fun setEnabled(enabled: Boolean) {
        gate?.await()
        check(!writeFails) { "write failed" }
        settings.update { it.copy(enabled = enabled) }
    }

    override suspend fun setDownloadConsent(consent: Boolean) {
        gate?.await()
        check(!writeFails) { "write failed" }
        consents += consent
        settings.update {
            it.copy(downloadConsent = consent, status = if (consent) AiModelStatus.DOWNLOADING else it.status)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AiSettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val source = FakeAiSettingsSource()

    private fun TestScope.viewModel(): Pair<AiSettingsViewModel, StateFlow<AiSettingsUiState>> {
        val vm = AiSettingsViewModel(source)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        return vm to vm.state
    }

    @Test
    fun `shows the status, the switch and the consent row while the model can be downloaded`() = runTest {
        val (_, state) = viewModel()
        assertFalse(state.value.isLoading)
        assertEquals(AiSettings(AiModelStatus.DOWNLOADABLE, true, false), state.value.settings)
        assertTrue(state.value.showsDownloadConsent)
        assertTrue(state.value.canChangeConsent)

        source.settings.value = AiSettings(AiModelStatus.UNAVAILABLE, enabled = true, downloadConsent = false)
        assertFalse(state.value.showsDownloadConsent, "no consent row without AICore")
        source.settings.value = AiSettings(AiModelStatus.AVAILABLE, enabled = true, downloadConsent = true)
        assertFalse(state.value.showsDownloadConsent, "nothing to download")
        source.settings.value = AiSettings(AiModelStatus.DOWNLOADING, enabled = true, downloadConsent = true)
        assertTrue(state.value.showsDownloadConsent)
    }

    @Test
    fun `consenting asks for the download once and the status follows`() = runTest {
        val (vm, state) = viewModel()

        vm.onEvent(AiSettingsEvent.DownloadConsentChanged(true))

        assertEquals(listOf(true), source.consents)
        assertEquals(AiModelStatus.DOWNLOADING, state.value.settings?.status)
        assertTrue(state.value.settings?.downloadConsent == true)
    }

    @Test
    fun `turning AI off is written and blocks the consent`() = runTest {
        val (vm, state) = viewModel()

        vm.onEvent(AiSettingsEvent.EnabledChanged(false))
        assertEquals(false, state.value.settings?.enabled)
        assertFalse(state.value.canChangeConsent)

        vm.onEvent(AiSettingsEvent.DownloadConsentChanged(true))
        assertTrue(source.consents.isEmpty(), "no download while AI is off")
    }

    @Test
    fun `one write at a time`() = runTest {
        val gate = CompletableDeferred<Unit>()
        source.gate = gate
        val (vm, state) = viewModel()

        vm.onEvent(AiSettingsEvent.DownloadConsentChanged(true))
        assertTrue(state.value.isSaving)
        vm.onEvent(AiSettingsEvent.EnabledChanged(false))
        gate.complete(Unit)

        assertFalse(state.value.isSaving)
        assertEquals(listOf(true), source.consents)
        assertEquals(true, state.value.settings?.enabled, "the second tap was ignored while saving")
    }

    @Test
    fun `a failed write shows a message`() = runTest {
        source.writeFails = true
        val (vm, state) = viewModel()

        vm.onEvent(AiSettingsEvent.EnabledChanged(false))

        assertEquals(SettingsMessage.SAVE_FAILED, state.value.message)
        assertEquals(true, state.value.settings?.enabled)
        vm.onEvent(AiSettingsEvent.MessageShown)
        assertNull(state.value.message)
    }

    @Test
    fun `an unreadable source shows the load failure`() = runTest {
        source.fails = true
        val (_, state) = viewModel()
        assertTrue(state.value.loadFailed)
        assertNull(state.value.settings)
    }
}
