# Screenshot tests

Compose screens are screenshot-tested with [Roborazzi](https://github.com/takahirom/roborazzi) on Robolectric, as plain JVM unit tests (no emulator). Golden images are committed next to the tests in `<module>/src/test/screenshots/`, and CI fails when a screen stops matching its goldens.

## Setup in a module

```kotlin
plugins {
    alias(libs.plugins.sprout.android.feature) // or sprout.android.library.compose
    alias(libs.plugins.sprout.android.screenshot)
}
```

`sprout.android.screenshot` (build-logic) applies the Roborazzi Gradle plugin, sets the golden directory to `src/test/screenshots`, turns on `isIncludeAndroidResources` and `isReturnDefaultValues` for unit tests, and adds Robolectric, Roborazzi, Compose UI test and `:core:testing` as test dependencies.

Tests extend `ScreenshotTest` from `:core:testing` (`dev.kpetriv.sprout.core.testing.screenshot`) and call one capture function per test:

```kotlin
class WalletScreenshotTest : ScreenshotTest() {
    @Test
    @Config(qualifiers = "+h1500dp") // taller window for scrolling content
    fun `ready, every tier`() = screenshot.captureTiers("wallet_ready") { tier ->
        WalletScreen(state = WalletPreviewData.ready(tier), onEvent = {})
    }

    @Test
    fun error() = screenshot.captureLightDark("wallet_error") {
        WalletScreen(state = WalletUiState.Error(WalletError.LOAD_FAILED), onEvent = {})
    }
}
```

- `captureTiers(name) { tier -> ... }` renders inside `SproutTheme` + `ProvideKidTier(tier)` for Seedling, Sprout and Sapling and writes `<name>_seedling.png`, `<name>_sprout.png` and `<name>_sapling.png`. Every kid-mode screen gets one (CLAUDE.md).
- `captureLightDark(name) { ... }` writes `<name>_light.png` and `<name>_dark.png`.
- `capture(name) { ... }` writes `<name>.png`.
- `screenshot.compose` is the underlying Compose rule, for anything a test needs to do before capturing.

Content is drawn on a `Surface` with the theme's background colour. The image is the size of the Compose root: full-screen content fills the window, and a lone component (such as the adjustment form) is cropped to its own size.

`:core:testing` keeps these helpers in `src/main`. That is safe because the module is only ever a `testImplementation` dependency, so Robolectric, Roborazzi and JUnit never reach an APK. Dependency-Check skips the module for the same reason.

## Commands

| What | Command |
|---|---|
| Check against the goldens (what CI runs) | `./gradlew :feature:wallet:verifyRoborazziDebug` |
| Re-record the goldens after an intended UI change | `./gradlew :feature:wallet:recordRoborazziDebug` |
| Compare without failing; writes diffs for every change | `./gradlew :feature:wallet:compareRoborazziDebug` |
| All modules | drop the `:feature:wallet:` prefix |

Plain `./gradlew test` runs the screenshot tests as ordinary tests: the content must compose, but nothing is captured or compared. When `test` and a Roborazzi task are in one invocation (as in CI), each module's `testDebugUnitTest` still runs only once, in the Roborazzi mode. Do not set `roborazzi.test.record`, `roborazzi.test.compare` or `roborazzi.test.verify` in `gradle.properties`; the tasks set them.

Comparison allows a small tolerance (decision D-M2-11), set once as `ScreenshotRule.DEFAULT_OPTIONS` (the rule every `ScreenshotTest` uses, so it applies to every capture) through Roborazzi's `SimpleImageComparator`:

| Setting | Value | Meaning |
|---|---|---|
| `maxDistance` | `0.007` | how far a pixel's colour may move before it counts as changed |
| `hShift`, `vShift` | `1` | a pixel may match its golden counterpart up to 1 px away horizontally or vertically (anti-aliased edges) |
| `changeThreshold` | `0.001` | verification fails once more than 0.1 % of pixels count as changed |

The tolerance absorbs anti-aliasing noise between macOS and Linux (see below). It is not a licence for real changes: a moved component, a changed colour or different text still fails. Do not loosen these values to make a diff pass; re-record instead.

## Reviewing a diff

A failing verify writes three images per changed screenshot to `<module>/build/outputs/roborazzi/`:

- `<name>_actual.png`: what the code renders now
- `<name>_compare.png`: golden, diff and actual side by side, with changed pixels highlighted
- the failure message names the golden that no longer matches

Verification stops a test at its first mismatch, so later variants in the same test (for example `_dark` after `_light`) may have no images. Run `compareRoborazziDebug` to get diffs for all of them.

On CI the `screenshot-diffs` artifact holds the same files. If the change is intended, run `recordRoborazziDebug`, look at the new goldens in the PR diff (GitHub shows image diffs), and commit them with the code change. Never re-record just to make CI green without looking at what changed.

## Determinism rules

Goldens must come out byte-identical on every run. `ScreenshotTest` and `ScreenshotDevice` enforce the following; change them only together with a full re-record.

- **Robolectric SDK 35**, set in `ScreenshotTest`'s `@Config(sdk = ...)`, so a Robolectric upgrade cannot quietly change the default SDK. Modules may also pin `sdk=35` in `src/test/resources/robolectric.properties` for their other Robolectric tests. SDK 36 sandboxes need Java 21, and the build runs on 17.
- **Native graphics** (`@GraphicsMode(NATIVE)`): Robolectric draws with the real Android rendering stack.
- **One device**: `ScreenshotDevice.PIXEL_7` (411 x 914 dp at 420 dpi, portrait, `en-rGB`). Tests may only add overrides such as a taller window (`@Config(qualifiers = "+h1500dp")`).
- **Fixed JVM locale and time zone**: `FixedLocaleTimeZoneRule` (en-GB, UTC) wraps every test, so dates, numbers and currency format the same on every machine. Android resources follow the `en-rGB` qualifier.
- **Fixed data**: use preview data with fixed instants and ids (`WalletPreviewData`), never `Clock.System`, random values or real user data.
- **Animations**: Compose's test clock auto-advances until the UI is idle before each capture, so finite animations have finished. Infinite animations, such as an indeterminate progress indicator, are cancelled by the Compose test framework and draw their first frame. Do not capture in the middle of a finite animation.
- **Fonts**: Robolectric's bundled Android fonts (Roboto and Noto Color Emoji from the SDK 35 image). No downloadable or system fonts.
- **No state between variants**: each variant gets a fresh composition (`key(name)`), so `remember`ed state, scroll position or text input never leaks from one image into the next.
- **Size budget**: keep each golden under 300 KB. A full-screen Pixel 7 capture is about 20 to 250 KB. If a screen goes past the budget, crop the capture to the component, or set `roborazzi.record.resizeScale`.

### macOS and Linux

The committed goldens were recorded on macOS (Apple silicon), and CI verifies on Linux x86_64. Robolectric's native graphics use the same rendering code and bundled fonts on both, but anti-aliasing can still differ by a few pixels between platforms. The comparison tolerance above covers that noise for now.

To get Linux goldens, run the **`Record screenshots`** workflow (`workflow_dispatch`, from the Actions tab or `gh workflow run "Record screenshots" --ref <branch>`). It runs `recordRoborazziDebug` on a Linux x86_64 runner and uploads the full set as an artifact. Download it, check in the PR diff that the differences from the macOS set are only edge noise, and commit it. The CI job's `screenshots-linux` artifact (recorded on the runner when the unit and screenshot test step fails; a lint or Detekt failure does not trigger it) is the same set and can be used the same way.

**Policy: once goldens have been recorded on Linux, Linux goldens are the source of truth.** From then on, record only with the `Record screenshots` workflow or on another Linux x86_64 machine, never on macOS. Goldens recorded on macOS after that point are rejected in review.
