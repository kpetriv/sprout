package dev.kpetriv.sprout.feature.chores

import dev.kpetriv.sprout.core.designsystem.components.Pictogram
import org.junit.Test
import kotlin.test.assertEquals

/** The keyword map of 1.0, now to pictograms (M9 §2.7): same keys, first match wins, the sparkle otherwise. */
class ChorePictogramTest {
    @Test
    fun `titles map to their pictogram by keyword`() {
        val expected = mapOf(
            "Make your bed" to Pictogram.BED,
            "Brush teeth" to Pictogram.TOOTH,
            "Empty the dishwasher" to Pictogram.DISHES,
            "Set the table" to Pictogram.CUTLERY,
            "Tidy toys" to Pictogram.TEDDY,
            "Clean your room" to Pictogram.BROOM,
            "Fold the laundry" to Pictogram.LAUNDRY,
            "Water the plants" to Pictogram.SEEDLING,
            "Walk the dog" to Pictogram.PAW,
            "Take the bins out" to Pictogram.BIN,
            "Read for 20 minutes" to Pictogram.BOOK,
            "Do your homework" to Pictogram.PENCIL,
            "Line up your shoes" to Pictogram.SHOE,
            "Have a bath" to Pictogram.BATH,
            "Wash the car" to Pictogram.BATH,
        )
        expected.forEach { (title, pictogram) -> assertEquals(pictogram, ChorePictogram.forTitle(title), title) }
    }

    @Test
    fun `the first matching rule wins and anything else gets the sparkle`() {
        // "bed" is the first rule, so it wins over "teeth"; case does not matter.
        assertEquals(Pictogram.BED, ChorePictogram.forTitle("BRUSH YOUR TEETH BEFORE BED"))
        assertEquals(Pictogram.CAR, ChorePictogram.forTitle("Hoover the car seats"))
        assertEquals(ChorePictogram.DEFAULT, ChorePictogram.forTitle("Help cook dinner"))
        assertEquals(Pictogram.SPARKLE, ChorePictogram.DEFAULT)
    }
}
