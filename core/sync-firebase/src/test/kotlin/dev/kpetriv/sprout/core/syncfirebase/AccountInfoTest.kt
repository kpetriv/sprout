package dev.kpetriv.sprout.core.syncfirebase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AccountInfoTest {
    @Test
    fun `toString never shows the e-mail address`() {
        val info = AccountInfo("parent@example.com")

        assertFalse("parent@example.com" in info.toString())
        assertFalse("example" in info.toString())
        assertEquals("AccountInfo(email=<redacted>)", info.toString())
        assertEquals("AccountInfo(email=none)", AccountInfo(null).toString())
    }

    @Test
    fun `equality is by address`() {
        assertEquals(AccountInfo("a@example.com"), AccountInfo("a@example.com"))
        assertFalse(AccountInfo("a@example.com") == AccountInfo("b@example.com"))
    }
}
