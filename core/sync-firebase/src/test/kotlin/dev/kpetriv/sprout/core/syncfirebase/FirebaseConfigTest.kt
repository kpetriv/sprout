package dev.kpetriv.sprout.core.syncfirebase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirebaseConfigTest {
    @Test
    fun `without a base URL callables go to Cloud Functions in the region`() {
        val config = FirebaseConfig(emulatorHost = null, region = "europe-west2", debugAppCheck = false)

        assertEquals(FunctionsEndpoint.Region("europe-west2"), config.functionsEndpoint)
        assertFalse(config.usesFunctionsEmulator)
    }

    @Test
    fun `a base URL is used as the custom domain and replaces the Functions emulator`() {
        val config = FirebaseConfig(
            emulatorHost = "10.0.2.2",
            debugAppCheck = true,
            functionsBaseUrl = "http://10.0.2.2:8787/",
        )

        assertEquals(FunctionsEndpoint.CustomDomain("http://10.0.2.2:8787"), config.functionsEndpoint)
        assertEquals("http://10.0.2.2:8787", config.functionsEndpoint.value)
        assertFalse(config.usesFunctionsEmulator, "the Functions emulator is gone (D-M3-29)")
    }

    @Test
    fun `the Functions emulator is used only with an emulator host and no base URL`() {
        assertTrue(FirebaseConfig(emulatorHost = "10.0.2.2", debugAppCheck = true).usesFunctionsEmulator)
        val production =
            FirebaseConfig(emulatorHost = null, debugAppCheck = false, functionsBaseUrl = "https://api.example.com")
        assertEquals(FunctionsEndpoint.CustomDomain("https://api.example.com"), production.functionsEndpoint)
        assertFalse(production.usesFunctionsEmulator)
    }

    @Test
    fun `only a deleted or disabled account counts as gone`() {
        assertTrue(FirebaseAuthSession.isUserGone("ERROR_USER_NOT_FOUND"))
        assertTrue(FirebaseAuthSession.isUserGone("ERROR_USER_DISABLED"))
        assertFalse(FirebaseAuthSession.isUserGone("ERROR_USER_TOKEN_EXPIRED"))
        assertFalse(FirebaseAuthSession.isUserGone("ERROR_INVALID_USER_TOKEN"))
    }
}
