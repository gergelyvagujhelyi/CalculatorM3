package com.vagujhelyigergely.calculatorm3.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkGeometryTest {

    private fun stroke(vararg xy: Float) = InkStroke(xy.toList().chunked(2).map { InkPoint(it[0], it[1]) })
    private val half = InkGeometry.STROKE_WIDTH / 2

    @Test
    fun `no ink has no bounds`() {
        assertNull(InkGeometry.bounds(emptyList()))
        assertNull(InkGeometry.bounds(listOf(InkStroke(emptyList()))))
    }

    @Test
    fun `bounds cover every stroke plus half the pen`() {
        val b = InkGeometry.bounds(listOf(stroke(0.2f, 0.3f, 0.4f, 0.5f), stroke(0.1f, 0.6f)))!!
        assertEquals(0.1f - half, b.left, 1e-6f)
        assertEquals(0.3f - half, b.top, 1e-6f)
        assertEquals(0.4f + half, b.right, 1e-6f)
        assertEquals(0.6f + half, b.bottom, 1e-6f)
    }

    @Test
    fun `the eraser hits a stroke near any of its segments, the newest first`() {
        val strokes = listOf(stroke(0f, 0f, 1f, 0f), stroke(0.5f, -0.5f, 0.5f, 0.5f), stroke(0.9f, 0.9f))
        assertEquals(1, InkGeometry.strokeAt(strokes, InkPoint(0.5f, 0.01f), 0.02f)) // both: newest wins
        assertEquals(0, InkGeometry.strokeAt(strokes, InkPoint(0.2f, 0.015f), 0.02f))
        assertEquals(2, InkGeometry.strokeAt(strokes, InkPoint(0.91f, 0.9f), 0.02f)) // a dot
        assertNull(InkGeometry.strokeAt(strokes, InkPoint(0.2f, 0.3f), 0.02f))
    }

    @Test
    fun `the longest side of the picture is the requested size`() {
        val wide = InkGeometry.fit(InkBounds(0f, 0f, 0.8f, 0.4f), 768)
        assertEquals(768, wide.widthPx)
        val tall = InkGeometry.fit(InkBounds(0f, 0f, 0.2f, 0.6f), 768)
        assertEquals(768, tall.heightPx)
    }

    @Test
    fun `the ink is centred with a margin on every side`() {
        val b = InkBounds(0.2f, 0.1f, 0.6f, 0.3f)
        val fit = InkGeometry.fit(b, 768)
        val left = b.left * fit.scale + fit.offsetX
        val right = b.right * fit.scale + fit.offsetX
        val top = b.top * fit.scale + fit.offsetY
        val bottom = b.bottom * fit.scale + fit.offsetY
        assertEquals(left, fit.widthPx - right, 1f)
        assertEquals(top, fit.heightPx - bottom, 1f)
        assertTrue("margin left: $left", left > 0.05f * fit.widthPx)
        assertTrue("margin top: $top", top > 0f)
    }

    @Test
    fun `a long single line is not squashed into a sliver`() {
        val fit = InkGeometry.fit(InkBounds(0f, 0f, 1f, 0.02f), 768)
        assertTrue("height ${fit.heightPx}", fit.heightPx >= 768 / 4)
    }

    @Test
    fun `the line width stays readable for tiny and huge drawings`() {
        val tiny = InkGeometry.fit(InkBounds(0f, 0f, 0.02f, 0.02f), 768)
        val huge = InkGeometry.fit(InkBounds(0f, 0f, 5f, 5f), 768)
        assertEquals(12f, tiny.lineWidthPx, 0f)
        assertEquals(3f, huge.lineWidthPx, 0f)
    }
}
