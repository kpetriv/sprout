package dev.kpetriv.sprout.core.testing.screenshot

/** The fixed Robolectric environment every screenshot test renders in. Changing any of these re-records all goldens. */
object ScreenshotDevice {
    /**
     * Robolectric SDK. Pinned (not "latest") so a Robolectric upgrade cannot silently change the rendering.
     * SDK 36 sandboxes need Java 21; the build runs on 17.
     */
    const val SDK = 35

    /**
     * A Pixel 7 (411 x 914 dp at 420 dpi, so 1080 x 2400 px) in British English, portrait, light mode.
     * Same values as Roborazzi's `RobolectricDeviceQualifiers.Pixel7`, plus the locale.
     * Tests whose content scrolls can make the window taller with `@Config(qualifiers = "+h1400dp")`.
     */
    const val PIXEL_7 = "en-rGB-w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav"

    /**
     * A 10" tablet in landscape: 1280 x 800 dp at mdpi (density 1.0, so 1280 x 800 px images) in British English. An
     * Expanded width class. Used by `ScreenshotRule.captureWidthClasses`; a test may also pin it with `@Config`.
     */
    const val TABLET = "en-rGB-w1280dp-h800dp-xlarge-notlong-notround-any-mdpi-keyshidden-nonav"

    /**
     * A 10" tablet in portrait: 800 x 1280 dp at mdpi (800 x 1280 px images) in British English. A Medium width
     * class (600 to 840 dp). Used by `ScreenshotWindow.TABLET_PORTRAIT` (M9-games §5, D-M9-G9).
     */
    const val TABLET_PORTRAIT = "en-rGB-w800dp-h1280dp-xlarge-notlong-notround-any-mdpi-keyshidden-nonav"
}
