package dev.kpetriv.sprout.core.crypto

import java.security.InvalidAlgorithmParameterException
import java.security.KeyStoreException
import java.security.ProviderException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StrongBoxFallbackTest {
    private val calls = mutableListOf<String>()

    private fun run(
        useStrongBox: Boolean = true,
        strongBox: () -> String = { "strongbox" },
        tee: () -> String = { "tee" },
        cleanUp: () -> Unit = {},
    ): String = withStrongBoxFallback(
        useStrongBox = useStrongBox,
        strongBox = {
            calls += "strongbox"
            strongBox()
        },
        tee = {
            calls += "tee"
            tee()
        },
        cleanUp = {
            calls += "cleanUp"
            cleanUp()
        },
    )

    private fun failWith(failure: Exception): () -> Nothing = { throw failure }

    @Test
    fun `strongbox success never touches the TEE`() {
        assertEquals("strongbox", run())
        assertEquals(listOf("strongbox"), calls)
    }

    @Test
    fun `no strongbox goes straight to the TEE`() {
        assertEquals("tee", run(useStrongBox = false))
        assertEquals(listOf("tee"), calls)
    }

    @Test
    fun `provider and keystore failures clean up then fall back to the TEE`() {
        val failures = listOf(
            ProviderException("generic provider failure"),
            // Stand-in for android.security.keystore.StrongBoxUnavailableException, a ProviderException subclass.
            object : ProviderException("subclass") {},
            KeyStoreException("keystore failure"),
            object : KeyStoreException("subclass") {},
        )
        for (failure in failures) {
            calls.clear()
            assertEquals("tee", run(strongBox = failWith(failure)), failure.message)
            assertEquals(listOf("strongbox", "cleanUp", "tee"), calls, failure.message)
        }
    }

    @Test
    fun `other exceptions propagate without fallback`() {
        val failure = IllegalStateException("bug")
        val thrown = assertFailsWith<IllegalStateException> { run(strongBox = failWith(failure)) }
        assertSame(failure, thrown)
        assertEquals(listOf("strongbox"), calls)

        calls.clear()
        assertFailsWith<InvalidAlgorithmParameterException> {
            run(strongBox = failWith(InvalidAlgorithmParameterException("bad spec")))
        }
        assertEquals(listOf("strongbox"), calls)
    }

    @Test
    fun `cleanup failure is recorded and the TEE is still tried`() {
        val strongBoxFailure = ProviderException("strongbox")
        val teeFailure = ProviderException("tee")
        val cleanUpFailure = KeyStoreException("delete failed")
        val thrown = assertFailsWith<ProviderException> {
            run(strongBox = failWith(strongBoxFailure), tee = failWith(teeFailure), cleanUp = failWith(cleanUpFailure))
        }
        assertSame(teeFailure, thrown)
        assertEquals(listOf("strongbox", "cleanUp", "tee"), calls)
        assertSame(strongBoxFailure, thrown.suppressed.single())
        assertTrue(strongBoxFailure.suppressed.contains(cleanUpFailure))
    }

    @Test
    fun `cleanup failure alone does not stop a successful TEE fallback`() {
        val result = run(
            strongBox = failWith(ProviderException("strongbox")),
            cleanUp = failWith(KeyStoreException("delete failed")),
        )
        assertEquals("tee", result)
        assertEquals(listOf("strongbox", "cleanUp", "tee"), calls)
    }

    @Test
    fun `TEE failure of any kind carries the strongbox failure`() {
        val strongBoxFailure = KeyStoreException("strongbox")
        val teeFailure = IllegalStateException("tee")
        val thrown = assertFailsWith<IllegalStateException> {
            run(strongBox = failWith(strongBoxFailure), tee = failWith(teeFailure))
        }
        assertSame(teeFailure, thrown)
        assertSame(strongBoxFailure, thrown.suppressed.single())
    }
}
