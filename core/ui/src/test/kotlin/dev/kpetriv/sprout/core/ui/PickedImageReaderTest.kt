package dev.kpetriv.sprout.core.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The size [PickedImageReader] decodes a picked image at, decided in the header callback (D-M6-53). */
class PickedImageReaderTest {
    @Test
    fun smallImagesKeepTheirSizeAndLargeOnesScaleToTheLongSide() {
        assertEquals(1_000 to 800, PickedImageReader.targetSize(1_000, 800, 2_048))
        assertEquals(2_048 to 1_536, PickedImageReader.targetSize(4_000, 3_000, 2_048))
        assertEquals(960 to 1_280, PickedImageReader.targetSize(3_000, 4_000, 1_280))
        assertEquals(2_048 to 1, PickedImageReader.targetSize(100_000, 10, 2_048), "the short side keeps a pixel")
    }

    @Test
    fun cameraPhotosUpTo250MegapixelsPass() {
        assertEquals(2_048 to 1_536, PickedImageReader.targetSize(16_320, 12_240, 2_048)) // 200 MP.
        assertEquals(2_048 to 2_048, PickedImageReader.targetSize(15_811, 15_811, 2_048)) // Just under 250 MP.
    }

    @Test
    fun oversizedOrEmptySourcesAreRefusedBeforeDecoding() {
        assertNull(PickedImageReader.targetSize(30_000, 30_000, 2_048), "900 MP, a decompression bomb")
        assertNull(PickedImageReader.targetSize(15_812, 15_812, 2_048), "just over 250 MP")
        assertNull(PickedImageReader.targetSize(Int.MAX_VALUE, Int.MAX_VALUE, 2_048), "no overflow")
        assertNull(PickedImageReader.targetSize(1_000_000, 1_000, 2_048), "1 000 MP in a strip")
        assertNull(PickedImageReader.targetSize(0, 100, 2_048))
        assertNull(PickedImageReader.targetSize(-1, -1, 2_048))
    }
}
