package com.vagujhelyigergely.calculatorm3

import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.camera.InkPoint
import com.vagujhelyigergely.calculatorm3.camera.InkRasterizer
import com.vagujhelyigergely.calculatorm3.camera.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The picture made from the pad: black ink on white, cropped, longest side as asked. */
class InkRasterizerTest {

    private val plus = listOf(
        InkStroke(listOf(InkPoint(0.2f, 0.5f), InkPoint(0.4f, 0.5f))),
        InkStroke(listOf(InkPoint(0.3f, 0.4f), InkPoint(0.3f, 0.6f))),
    )

    @Test
    fun noInkMakesNoPicture() {
        assertNull(InkRasterizer.render(emptyList(), 768))
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        assertNull(InkRasterizer.writeJpeg(emptyList(), dir, 768))
    }

    @Test
    fun inkIsBlackOnWhiteCroppedAndCentred() {
        val bitmap = InkRasterizer.render(plus, 768)!!
        try {
            assertEquals(768, maxOf(bitmap.width, bitmap.height))
            // Corners are paper; the middle of the plus is ink.
            assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
            assertEquals(Color.WHITE, bitmap.getPixel(bitmap.width - 1, bitmap.height - 1))
            val centre = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue("centre is dark: ${Integer.toHexString(centre)}", Color.red(centre) < 64)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun aSingleTapIsADot() {
        val bitmap = InkRasterizer.render(listOf(InkStroke(listOf(InkPoint(0.5f, 0.5f)))), 768)!!
        try {
            val centre = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue("dot drawn: ${Integer.toHexString(centre)}", Color.red(centre) < 64)
        } finally {
            bitmap.recycle()
        }
    }
}
