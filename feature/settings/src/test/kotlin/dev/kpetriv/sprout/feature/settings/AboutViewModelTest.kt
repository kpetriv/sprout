package dev.kpetriv.sprout.feature.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeUrlOpener(var opens: Boolean = true) : UrlOpener {
    val opened = mutableListOf<String>()

    override fun open(url: String): Boolean {
        opened += url
        return opens
    }
}

class AboutViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val info = AppBuildInfo(
        versionName = "1.2.0",
        versionCode = 345,
        privacyUrl = "https://sprout.example/privacy",
        securityUrl = "https://sprout.example/.well-known/security.txt",
    )
    private val opener = FakeUrlOpener()
    private val libraries = SettingsPreviewData.about.libraries.orEmpty()

    private fun viewModel(
        buildInfo: AppBuildInfo = info,
        licences: OpenSourceLicences = OpenSourceLicences { libraries },
    ) = AboutViewModel(buildInfo, opener, licences)

    @Test
    fun `shows the version, the build number and both policy rows`() {
        val state = viewModel().state.value

        assertEquals("1.2.0", state.versionName)
        assertEquals(345, state.versionCode)
        assertTrue(state.hasPrivacyPolicy)
        assertTrue(state.hasSecurityPolicy)
        assertEquals(libraries, state.libraries)
        assertFalse(state.licencesFailed)
    }

    @Test
    fun `the policy rows open the configured pages`() {
        val vm = viewModel()

        vm.onEvent(AboutEvent.OpenPrivacyPolicy)
        vm.onEvent(AboutEvent.OpenSecurityPolicy)

        assertEquals(listOf(info.privacyUrl, info.securityUrl), opener.opened)
        assertNull(vm.state.value.message)
    }

    @Test
    fun `a page nothing can open says so once`() {
        opener.opens = false
        val vm = viewModel()

        vm.onEvent(AboutEvent.OpenPrivacyPolicy)
        assertEquals(SettingsMessage.LINK_NOT_OPENED, vm.state.value.message)

        vm.onEvent(AboutEvent.MessageShown)
        assertNull(vm.state.value.message)
    }

    @Test
    fun `an unconfigured or non-web policy URL hides its row and is never opened`() {
        val vm = viewModel(info.copy(privacyUrl = null, securityUrl = "javascript:alert(1)"))

        assertFalse(vm.state.value.hasPrivacyPolicy)
        assertFalse(vm.state.value.hasSecurityPolicy)
        vm.onEvent(AboutEvent.OpenSecurityPolicy)
        assertEquals(emptyList(), opener.opened)
        assertEquals(SettingsMessage.LINK_NOT_OPENED, vm.state.value.message)
    }

    @Test
    fun `a library opens its licence text, else its page, and only web links`() {
        val vm = viewModel()
        val apache = libraries.first()
        val pageOnly = apache.copy(licences = listOf(LicenceLink("Custom", null)))
        val ftp = apache.copy(url = "ftp://example.org", licences = listOf(LicenceLink("Custom", "file:///etc/hosts")))

        vm.onEvent(AboutEvent.OpenLibrary(apache))
        vm.onEvent(AboutEvent.OpenLibrary(pageOnly))
        vm.onEvent(AboutEvent.OpenLibrary(ftp))

        assertEquals(listOf(apache.licences.first().url, apache.url), opener.opened)
        assertNull(ftp.link)
        assertEquals(SettingsMessage.LINK_NOT_OPENED, vm.state.value.message)
    }

    @Test
    fun `the licence list loads once and a failure is shown`() = runTest {
        val gate = CompletableDeferred<List<OpenSourceLibrary>>()
        val loading = viewModel(licences = OpenSourceLicences { gate.await() })
        assertNull(loading.state.value.libraries)
        assertFalse(loading.state.value.licencesFailed)
        gate.complete(libraries)
        assertEquals(libraries, loading.state.value.libraries)

        val failed = viewModel(licences = OpenSourceLicences { error("asset missing") })
        assertNull(failed.state.value.libraries)
        assertTrue(failed.state.value.licencesFailed)
    }
}
