package dev.kpetriv.sprout.core.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** RFC 5869 appendix A, SHA-256 cases. */
class HkdfTest {
    @Test
    fun `RFC 5869 test case 1`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val prk = Hkdf.extract(salt, ikm)
        assertContentEquals(hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"), prk)
        val expected = hex(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
        )
        assertContentEquals(expected, Hkdf.expand(prk, info, 42))
        assertContentEquals(expected, Hkdf.derive(ikm, salt, info, 42))
    }

    @Test
    fun `RFC 5869 test case 2`() {
        val ikm = ByteArray(80) { it.toByte() }
        val salt = ByteArray(80) { (0x60 + it).toByte() }
        val info = ByteArray(80) { (0xb0 + it).toByte() }
        val prk = Hkdf.extract(salt, ikm)
        assertContentEquals(hex("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244"), prk)
        val expected = hex(
            """
            b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c
            59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71
            cc30c58179ec3e87c14c01d5c1f3434f1d87
            """,
        )
        assertContentEquals(expected, Hkdf.derive(ikm, salt, info, 82))
    }

    @Test
    fun `RFC 5869 test case 3 with empty salt and info`() {
        val ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        val prk = Hkdf.extract(ByteArray(0), ikm)
        assertContentEquals(hex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"), prk)
        assertContentEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            Hkdf.derive(ikm, ByteArray(0), ByteArray(0), 42),
        )
    }

    @Test
    fun `output length is bounded`() {
        val prk = ByteArray(32) { 1 }
        assertFailsWith<IllegalArgumentException> { Hkdf.expand(prk, ByteArray(0), 0) }
        assertFailsWith<IllegalArgumentException> { Hkdf.expand(prk, ByteArray(0), 255 * 32 + 1) }
        Hkdf.expand(prk, ByteArray(0), 255 * 32)
    }
}
