package dev.kpetriv.sprout.feature.lock

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PinRulesTest {
    private fun check(pin: String) = PinRules.validate(pin.toCharArray())

    @Test
    fun `six varied digits are accepted`() {
        listOf("482913", "102938", "135790", "121212", "123465", "000001", "890123").forEach {
            assertNull(check(it), it)
        }
    }

    @Test
    fun `anything but exactly six characters is refused`() {
        listOf("", "12345", "4829130").forEach { assertEquals(PinRuleViolation.WRONG_LENGTH, check(it), it) }
    }

    @Test
    fun `only ASCII digits are allowed`() {
        listOf("48291a", "4829 3", "４８２９１３", "-48291").forEach {
            assertEquals(PinRuleViolation.NOT_DIGITS, check(it), it)
        }
    }

    @Test
    fun `a repeated digit is refused`() {
        listOf("000000", "111111", "999999").forEach { assertEquals(PinRuleViolation.ALL_SAME, check(it), it) }
    }

    @Test
    fun `straight runs up or down are refused`() {
        listOf("123456", "654321", "012345", "456789", "987654", "543210").forEach {
            assertEquals(PinRuleViolation.SEQUENCE, check(it), it)
        }
    }
}
