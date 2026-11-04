package dev.kpetriv.sprout.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

private const val TIMEOUT_MS = 15_000L

/**
 * How long a start may take to show its first screen. Startup work (the encrypted database, the ledger check, the
 * benchmark seed on a fresh install) runs behind the loading indicator; [startWithoutAccessibility] normally waits for
 * it, and this bounds the rest (D-V1-11).
 */
private const val START_TIMEOUT_MS = 180_000L
private const val SHORT_TIMEOUT_MS = 2_000L
private const val MAX_STEPS = 8
private const val MAX_ROUNDS = 16
private const val SCROLL_PERCENT = 0.8f

private const val DASHBOARD = "Family dashboard"
private const val PIN_SETUP = "Choose a parent PIN"
private const val PIN_SETUP_CONFIRM = "Enter the PIN again"
private const val PIN_ENTRY = "Enter your parent PIN"
private const val LEAVE_KID_MODE = "Parent PIN to leave kid mode"
private const val KID_MODE = "Switch to kid mode"
private const val GAMES = "Games"
private const val NUMBERS = "Numbers"
private const val GAMES_HUB = "Brain games"

/** The title of [Demo.KID]'s wallet (the apostrophe may be typographic). */
private val KID_WALLET: BySelector = By.text(Pattern.compile("${Demo.KID}.s wallet"))

/** The wallet's link to today's chores, at the top of the wallet ("🧹  Chores"). */
private val CHORES_LINK: BySelector = By.text(Pattern.compile(".*Chores"))

/** Any screen the app can open on, so a journey can tell where a cold start landed. */
private val ENTRY_SCREENS: BySelector = By.text(
    Pattern.compile("$DASHBOARD|$PIN_SETUP|$PIN_ENTRY|$LEAVE_KID_MODE|.*.s wallet"),
)

/** The text [selector] matches, waited for; fails the journey with [what] when it never shows. */
private fun MacrobenchmarkScope.await(selector: BySelector, what: String, timeout: Long = TIMEOUT_MS): UiObject2 =
    checkNotNull(device.wait(Until.findObject(selector), timeout)) { "Not on screen: $what" }

private fun MacrobenchmarkScope.await(text: String, timeout: Long = TIMEOUT_MS): UiObject2 =
    await(By.text(text), text, timeout)

/**
 * Clicks what [selector] matches once it shows. Compose replaces nodes as it recomposes, so a node found a moment ago
 * can be stale by the time it is clicked: it is then looked up again.
 */
@Suppress("SwallowedException") // A stale node is looked up again; the last attempt throws.
private fun MacrobenchmarkScope.tap(selector: BySelector, what: String) {
    repeat(STALE_RETRIES) {
        try {
            await(selector, what).click()
            return
        } catch (stale: StaleObjectException) {
            device.waitForIdle()
        }
    }
    await(selector, what).click()
}

private fun MacrobenchmarkScope.has(text: String): Boolean = device.hasObject(By.text(text))

/** One PIN pad key: its digit as text, or as the content description an accessible pad gives it instead. */
private fun MacrobenchmarkScope.pinKey(digit: Char): UiObject2 {
    val selectors = listOf(
        By.text(digit.toString()).clickable(true),
        By.clickable(true).hasDescendant(By.text(digit.toString())),
        By.desc(Pattern.compile("(?i)(.*\\D)?$digit")).clickable(true),
    )
    val deadline = System.currentTimeMillis() + SHORT_TIMEOUT_MS
    do {
        selectors.firstNotNullOfOrNull { device.findObject(it) }?.let { return it }
        device.waitForIdle()
    } while (System.currentTimeMillis() < deadline)
    return await(selectors.first(), "PIN key $digit")
}

/** Types [Demo.PIN] on the PIN pad on screen. */
@Suppress("SwallowedException") // A stale key is looked up again once; the second click throws.
private fun MacrobenchmarkScope.enterPin() {
    await(By.clickable(true).hasDescendant(By.text("5")), "PIN pad")
    Demo.PIN.forEach { digit ->
        try {
            pinKey(digit).click()
        } catch (stale: StaleObjectException) {
            pinKey(digit).click()
        }
        device.waitForIdle()
    }
}

/**
 * From wherever a start landed (first run: PIN setup; later: PIN entry; or the kid mode an earlier journey left the
 * app in) to the unlocked parent dashboard.
 */
internal fun MacrobenchmarkScope.reachParentDashboard() {
    await(ENTRY_SCREENS, "a start screen", START_TIMEOUT_MS)
    repeat(MAX_STEPS) {
        when {
            has(DASHBOARD) -> return

            has(PIN_SETUP) -> {
                enterPin()
                await(PIN_SETUP_CONFIRM)
                enterPin()
                device.wait(Until.gone(By.text(PIN_SETUP_CONFIRM)), TIMEOUT_MS)
            }

            has(PIN_ENTRY) -> {
                enterPin()
                device.wait(Until.gone(By.text(PIN_ENTRY)), TIMEOUT_MS)
            }

            has(LEAVE_KID_MODE) -> {
                enterPin()
                device.wait(Until.gone(By.text(LEAVE_KID_MODE)), TIMEOUT_MS)
            }

            // Kid mode at its root: back asks for the parent PIN (KidModeExitGate).
            else -> device.pressBack()
        }
        device.wait(Until.hasObject(ENTRY_SCREENS), START_TIMEOUT_MS)
    }
    await(DASHBOARD)
}

/**
 * Cold start to the unlocked parent dashboard: the start until the app reports itself fully drawn (the PIN screen,
 * D-M7-43), then the PIN and the dashboard's first frame of content.
 */
internal fun MacrobenchmarkScope.startToParentDashboard() {
    startWithoutAccessibility()
    reachParentDashboard()
}

/** Parent dashboard → "Switch to kid mode" → [Demo.KID]: the kid's wallet in kid mode. */
internal fun MacrobenchmarkScope.enterKidMode() {
    reachParentDashboard()
    scrollTo(By.text(KID_MODE), KID_MODE)
    tap(By.text(KID_MODE), KID_MODE)
    tap(By.text(Demo.KID), Demo.KID)
    await(KID_WALLET, "${Demo.KID}'s wallet")
    device.waitForIdle()
}

/** Scrolls the screen's list down until [selector] shows, then returns it. */
private fun MacrobenchmarkScope.scrollTo(selector: BySelector, what: String): UiObject2 {
    repeat(MAX_STEPS) {
        device.wait(Until.findObject(selector), SHORT_TIMEOUT_MS)?.let { return it }
        val list = await(By.scrollable(true), "a list")
        list.setGestureMargin(device.displayHeight / GESTURE_MARGIN_DIVISOR)
        list.scroll(Direction.DOWN, SCROLL_PERCENT)
        device.waitForIdle()
    }
    return await(selector, what)
}

/** The kid's wallet in kid mode: stays there when a previous iteration left it there, otherwise [enterKidMode]. */
internal fun MacrobenchmarkScope.reachKidWallet() {
    await(ENTRY_SCREENS, "a start screen", START_TIMEOUT_MS)
    if (device.hasObject(KID_WALLET)) return
    enterKidMode()
}

/** Flings down the kid's wallet history and back to the top (the frame timing journey). */
internal fun MacrobenchmarkScope.scrollKidWallet() {
    val list = await(By.scrollable(true), "the wallet list")
    list.setGestureMargin(device.displayWidth / GESTURE_MARGIN_DIVISOR)
    // fling()'s "can scroll further" answer is unreliable for a lazy list, so: a fixed number of flings down, then up
    // until the top (the chores link) shows again.
    repeat(FLINGS) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
    }
    repeat(MAX_STEPS) {
        if (device.hasObject(CHORES_LINK)) return
        list.fling(Direction.UP)
        device.waitForIdle()
    }
}

/** Kid wallet → today's chores and back. */
internal fun MacrobenchmarkScope.openChoresToday() {
    tap(CHORES_LINK, "the chores link")
    await(By.text(Pattern.compile("Today|No chores today!")), "today's chores")
    device.waitForIdle()
    device.pressBack()
    await(KID_WALLET, "${Demo.KID}'s wallet")
}

/** Kid wallet → games hub → one arithmetic game, answered round by round until its result, then back. */
internal fun MacrobenchmarkScope.playArithmeticRound() {
    tap(By.text(Pattern.compile(".*$GAMES")), "the games shortcut")
    await(GAMES_HUB)
    tap(By.text(NUMBERS), NUMBERS)
    tap(By.text("Start"), "Start")
    var rounds = 0
    while (rounds < MAX_ROUNDS && !device.wait(Until.hasObject(By.text("Done")), SHORT_TIMEOUT_MS)) {
        answerOnce()
        rounds++
    }
    tap(By.text("Done"), "Done")
    await(GAMES_HUB)
    device.pressBack()
    await(KID_WALLET, "${Demo.KID}'s wallet")
}

/** Answers the round on screen: types 1 and checks it, or taps the first choice. */
private fun MacrobenchmarkScope.answerOnce() {
    val field = device.findObject(By.clazz("android.widget.EditText"))
    if (field != null) {
        field.text = "1"
        device.wait(Until.findObject(By.text("Check").enabled(true)), SHORT_TIMEOUT_MS)?.click()
    } else {
        device.findObject(By.desc(Pattern.compile("\\d+")).clickable(true))?.click()
    }
    device.waitForIdle()
}

/** Back from kid mode to the parent dashboard, so the next iteration starts where a parent would. */
internal fun MacrobenchmarkScope.leaveKidMode() {
    device.pressBack()
    reachParentDashboard()
}

private const val GESTURE_MARGIN_DIVISOR = 5
private const val STALE_RETRIES = 3
private const val FLINGS = 3
