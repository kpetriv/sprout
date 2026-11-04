package dev.kpetriv.sprout.widget

import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The widget's three states (M7 §4, D-M7-1), composed by Glance's unit-test harness at both sizes (`hasText` matches
 * substrings).
 */
@RunWith(RobolectricTestRunner::class)
class SproutWidgetContentTest {
    private val kid = WidgetState.Kid(
        name = "Mia",
        balance = "£12.50",
        minutes = "45 min",
        dueToday = 3,
        toDo = 2,
        firstToDo = listOf("Tidy room", "Feed the cat"),
    )

    @Test
    fun `a linked kid phone shows the name, balance, minutes and chores to do`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(SproutWidget.SMALL)
        provideComposable { SproutWidgetContent(kid, open = null) }

        onNode(hasText("Mia")).assertExists()
        onNode(hasText("£12.50")).assertExists()
        onNode(hasText("45 min screen time")).assertExists()
        onNode(hasText("2 chores to do today")).assertExists()
        // The 2×2 layout has no room for the titles.
        onNode(hasText("• Tidy room")).assertDoesNotExist()
        onNode(hasText("Open Sprout")).assertDoesNotExist()
    }

    @Test
    fun `the wide layout adds the first two chores to do`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(SproutWidget.WIDE)
        provideComposable { SproutWidgetContent(kid, open = null) }

        onNode(hasText("2 chores to do today")).assertExists()
        onNode(hasText("• Tidy room")).assertExists()
        onNode(hasText("• Feed the cat")).assertExists()
    }

    @Test
    fun `chores line says when everything is done or nothing is due`() {
        runGlanceAppWidgetUnitTest {
            setContext(ApplicationProvider.getApplicationContext())
            setAppWidgetSize(SproutWidget.SMALL)
            provideComposable { SproutWidgetContent(kid.copy(toDo = 0, firstToDo = emptyList()), open = null) }
            onNode(hasText("Nothing left to do today")).assertExists()
        }
        runGlanceAppWidgetUnitTest {
            setContext(ApplicationProvider.getApplicationContext())
            setAppWidgetSize(SproutWidget.SMALL)
            provideComposable {
                SproutWidgetContent(kid.copy(dueToday = 0, toDo = 0, firstToDo = emptyList()), open = null)
            }
            onNode(hasText("No chores today")).assertExists()
        }
    }

    @Test
    fun `a kid without accounts shows no balance or minutes`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(SproutWidget.SMALL)
        provideComposable { SproutWidgetContent(kid.copy(balance = null, minutes = null), open = null) }

        onNode(hasText("Mia")).assertExists()
        onNode(hasText("£12.50")).assertDoesNotExist()
        onNode(hasText("screen time")).assertDoesNotExist()
    }

    @Test
    fun `elsewhere it only offers to open the app`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(SproutWidget.WIDE)
        provideComposable { SproutWidgetContent(WidgetState.OpenApp, open = null) }

        onNode(hasText("Open Sprout")).assertExists()
        onNode(hasText("Mia")).assertDoesNotExist()
    }

    @Test
    fun `blank while a removal or sign-in again is pending shows no text at all`() = runGlanceAppWidgetUnitTest {
        setContext(ApplicationProvider.getApplicationContext())
        setAppWidgetSize(SproutWidget.WIDE)
        provideComposable { SproutWidgetContent(WidgetState.Blank, open = null) }

        onNode(hasText("Open Sprout")).assertDoesNotExist()
        onNode(hasText("Mia")).assertDoesNotExist()
        onNode(hasText("chores")).assertDoesNotExist()
    }

    @Test
    fun `toString never prints the name or chore titles`() {
        val text = kid.toString()
        check("Mia" !in text && "Tidy" !in text && "12.50" !in text) { text }
    }

    private fun WidgetState.Kid.copy(
        balance: String? = this.balance,
        minutes: String? = this.minutes,
        dueToday: Int = this.dueToday,
        toDo: Int = this.toDo,
        firstToDo: List<String> = this.firstToDo,
    ) = WidgetState.Kid(name, balance, minutes, dueToday, toDo, firstToDo)
}
