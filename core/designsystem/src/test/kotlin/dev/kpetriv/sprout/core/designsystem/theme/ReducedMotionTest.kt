package dev.kpetriv.sprout.core.designsystem.theme

import android.provider.Settings
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReducedMotionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun reducedMotionUnderTheme(): Boolean {
        var reduced: Boolean? = null
        compose.setContent { SproutTheme { reduced = SproutTheme.reducedMotion } }
        compose.waitForIdle()
        return checkNotNull(reduced)
    }

    @Test
    fun `motion is on by default, so screenshots keep their animations' end states`() {
        assertEquals(false, reducedMotionUnderTheme())
    }

    @Test
    fun `Remove animations turns reduced motion on`() {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertEquals(true, reducedMotionUnderTheme())
    }
}
