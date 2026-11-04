package dev.kpetriv.sprout.core.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The decode budget of [MlKitReceiptTextReader] (audit L4); recognition itself needs a device. */
class MlKitReceiptTextReaderTest {
    @Test
    fun photosAreDecodedAtMostFourMegapixels() {
        assertEquals(1, MlKitReceiptTextReader.sampleSize(2000, 2000))
        assertEquals(2, MlKitReceiptTextReader.sampleSize(4000, 3000)) // 12 MP to 3 MP.
        assertEquals(4, MlKitReceiptTextReader.sampleSize(5000, 4000)) // 20 MP to 1.25 MP.
        assertEquals(2, MlKitReceiptTextReader.sampleSize(1000, 8000)) // A long receipt: 8 MP to 2 MP.
    }

    @Test
    fun hugeOrUnknownSizesAreRefusedBeforeDecoding() {
        assertNull(MlKitReceiptTextReader.sampleSize(60_000, 60_000), "a decompression bomb")
        assertNull(MlKitReceiptTextReader.sampleSize(5000, 4001), "just over 20 MP")
        assertNull(MlKitReceiptTextReader.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE), "no overflow")
        assertNull(MlKitReceiptTextReader.sampleSize(0, 100))
        assertNull(MlKitReceiptTextReader.sampleSize(-1, -1))
    }
}
