package dev.kpetriv.sprout.core.testing.screenshot

import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Base class for Roborazzi screenshot tests: Robolectric on a pinned SDK and device ([ScreenshotDevice]) with
 * native graphics, a fixed locale and time zone, and [screenshot] for capturing. The module applies
 * `sprout.android.screenshot`. Subclasses may narrow the device with `@Config(qualifiers = "+...")`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ScreenshotDevice.SDK], qualifiers = ScreenshotDevice.PIXEL_7)
abstract class ScreenshotTest {
    @get:Rule
    val screenshot = ScreenshotRule()
}
