package dev.kpetriv.sprout.core.testing.screenshot

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.Locale
import java.util.TimeZone

/**
 * Pins the JVM default [Locale] and [TimeZone] for one test and restores the previous values afterwards, so
 * dates, numbers and currency render the same on every machine. Android resources follow the locale in the
 * Robolectric qualifiers instead (see [ScreenshotDevice]); keep the two in step.
 */
class FixedLocaleTimeZoneRule(
    private val locale: Locale = Locale.UK,
    private val timeZone: TimeZone = TimeZone.getTimeZone("UTC"),
) : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val previousLocale = Locale.getDefault()
            val previousTimeZone = TimeZone.getDefault()
            Locale.setDefault(locale)
            TimeZone.setDefault(timeZone)
            try {
                base.evaluate()
            } finally {
                Locale.setDefault(previousLocale)
                TimeZone.setDefault(previousTimeZone)
            }
        }
    }
}
