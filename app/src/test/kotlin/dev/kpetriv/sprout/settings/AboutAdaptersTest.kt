package dev.kpetriv.sprout.settings

import android.app.Application
import android.content.Intent
import dev.kpetriv.sprout.BuildConfig
import dev.kpetriv.sprout.di.AboutModule
import dev.kpetriv.sprout.feature.settings.LicenceLink
import dev.kpetriv.sprout.feature.settings.OpenSourceLibrary
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** About's adapters (M8 §1): the browser intent, the generated licence asset and the build info. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class) // Not the Hilt app: its startup opens the encrypted database.
class AboutAdaptersTest {
    private val application: Application = RuntimeEnvironment.getApplication()

    @Test
    fun `a web link opens in a browsable view intent`() {
        assertTrue(IntentUrlOpener(application).open("https://sprout.example/privacy"))

        val intent = assertNotNull(shadowOf(application).nextStartedActivity)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://sprout.example/privacy", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `any other scheme is refused without starting anything`() {
        assertFalse(IntentUrlOpener(application).open("intent://evil#Intent;end"))
        assertFalse(IntentUrlOpener(application).open("file:///data/data/dev.kpetriv.sprout/files/x"))
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `the asset parses into libraries with their licences`() {
        val json = """
            {"libraries":[
            {"name":"Activity","module":"androidx.activity:activity","version":"1.13.0","url":null,
             "licences":[{"name":"Apache-2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"}],"extra":1},
            {"name":"Bare","module":"org.example:bare","version":"1","url":"https://example.org","licences":[]}
            ]}
        """.trimIndent()

        assertEquals(
            listOf(
                OpenSourceLibrary(
                    name = "Activity",
                    module = "androidx.activity:activity",
                    version = "1.13.0",
                    url = null,
                    licences = listOf(LicenceLink("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0")),
                ),
                OpenSourceLibrary("Bare", "org.example:bare", "1", "https://example.org", emptyList()),
            ),
            parseOpenSourceLicences(json),
        )
    }

    @Test
    fun `the build ships the generated licence list of its own classpath`() = runTest {
        val libraries = AssetOpenSourceLicences(application, StandardTestDispatcher(testScheduler)).load()

        val modules = libraries.map { it.module }
        assertTrue("androidx.activity:activity" in modules, "the generated asset lists the runtime classpath")
        assertTrue("net.zetetic:sqlcipher-android" in modules)
        assertTrue(libraries.all { it.licences.isNotEmpty() }, "every shipped library has a licence")
        assertFalse(modules.any { it.endsWith("-bom") }, "platforms never ship")
    }

    @Test
    fun `the build info comes from BuildConfig`() {
        val info = AboutModule.appBuildInfo()

        assertEquals(BuildConfig.VERSION_NAME, info.versionName)
        assertEquals(BuildConfig.VERSION_CODE.toLong(), info.versionCode)
        assertEquals(BuildConfig.PRIVACY_URL, info.privacyUrl)
        assertTrue(info.versionCode > 0)
    }
}
