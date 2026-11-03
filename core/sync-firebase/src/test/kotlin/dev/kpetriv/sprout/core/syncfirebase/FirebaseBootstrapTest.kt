package dev.kpetriv.sprout.core.syncfirebase

import kotlin.test.Test
import kotlin.test.assertTrue

class FirebaseBootstrapTest {
    @Test
    fun `the demo API key has the shape firebase-iid accepts`() {
        // firebase-iid rejects any key not matching this pattern with IllegalArgumentException, which broke every
        // callable in debug builds without google-services.json.
        assertTrue(Regex("AIza[0-9A-Za-z_-]{35}").matches(FirebaseBootstrap.DEMO_API_KEY))
    }

    @Test
    fun `the demo API key is an obvious placeholder`() {
        assertTrue(FirebaseBootstrap.DEMO_API_KEY.removePrefix("AIza").all { it == '0' })
    }
}
