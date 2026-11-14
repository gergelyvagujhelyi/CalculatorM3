package com.vagujhelyigergely.calculatorm3.camera

import kotlin.math.max
import kotlin.math.sqrt

/**
 * A point on the handwriting pad in pad units: fractions of the pad's width. Both axes use the
 * width, so a drawing keeps its shape and scales with the pad (rotation, foldables).
 */
data class InkPoint(val x: Float, val y: Float)

/** One continuous pen-down-to-pen-up line. A single point is a dot (a decimal point, ÷). */
data class InkStroke(val points: List<InkPoint>)

/** An axis-aligned box in pad units. */
data class InkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * How the ink is placed in the picture the model sees: [widthPx] × [heightPx], a pad point p
 * lands at (p.x × [scale] + [offsetX], p.y × [scale] + [offsetY]), drawn [lineWidthPx] wide.
 */
data class InkFit(
    val widthPx: Int,
    val heightPx: Int,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val lineWidthPx: Float,
)

/** Pure geometry for the pad: bounds, the eraser's hit test, and fitting ink into a picture. */
object InkGeometry {

    /** Pen width on the pad, in pad units (about 4 dp on a phone). */
    const val STROKE_WIDTH = 0.011f

    /** Bounds of all ink including half the pen width, or null when there's none. */
    fun bounds(strokes: List<InkStroke>): InkBounds? {
        val points = strokes.flatMap { it.points }
        if (points.isEmpty()) return null
        val half = STROKE_WIDTH / 2
        return InkBounds(
            points.minOf { it.x } - half, points.minOf { it.y } - half,
            points.maxOf { it.x } + half, points.maxOf { it.y } + half
        )
    }

    /**
     * The index of the most recent stroke that passes within [radius] of [point] (the eraser
     * removes whole strokes), or null if none does.
     */
    fun strokeAt(strokes: List<InkStroke>, point: InkPoint, radius: Float): Int? =
        strokes.indices.lastOrNull { i ->
            val pts = strokes[i].points
            when (pts.size) {
                0 -> false
                1 -> distance(point, pts[0]) <= radius
                else -> pts.zipWithNext().any { (a, b) -> segmentDistance(point, a, b) <= radius }
            }
        }

    /**
     * Fit the ink into a picture whose longest side is [maxEdge] px: crop to the ink, add a
     * margin of [margin] × the ink's longest side, and keep the short side at least a quarter of
     * [maxEdge] (a long line of writing stays readable, not a sliver). The line is drawn as wide
     * as on the pad, scaled, but kept between 3 and 12 px so a tiny scribble blown up doesn't turn
     * into blobs and a big one doesn't fade into hairlines.
     */
    fun fit(bounds: InkBounds, maxEdge: Int, margin: Float = 0.12f): InkFit {
        val pad = max(bounds.width, bounds.height) * margin
        var w = bounds.width + 2 * pad
        var h = bounds.height + 2 * pad
        // Keep the short side at least a quarter of the long one, centring the ink.
        if (h < w / 4) h = w / 4
        if (w < h / 4) w = h / 4
        val scale = maxEdge / max(w, h)
        val widthPx = (w * scale).toInt().coerceAtLeast(1)
        val heightPx = (h * scale).toInt().coerceAtLeast(1)
        val offsetX = (widthPx - bounds.width * scale) / 2 - bounds.left * scale
        val offsetY = (heightPx - bounds.height * scale) / 2 - bounds.top * scale
        return InkFit(widthPx, heightPx, scale, offsetX, offsetY,
            lineWidthPx = (STROKE_WIDTH * scale).coerceIn(3f, 12f))
    }

    private fun distance(a: InkPoint, b: InkPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /** Distance from [p] to the segment [a]–[b]. */
    private fun segmentDistance(p: InkPoint, a: InkPoint, b: InkPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return distance(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return distance(p, InkPoint(a.x + t * dx, a.y + t * dy))
    }
}
