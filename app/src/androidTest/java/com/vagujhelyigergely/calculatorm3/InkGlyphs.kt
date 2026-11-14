package com.vagujhelyigergely.calculatorm3

import com.vagujhelyigergely.calculatorm3.camera.InkPoint
import com.vagujhelyigergely.calculatorm3.camera.InkStroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hand-drawn-looking strokes for test problems: each character is a few polylines in a 0.6 × 1
 * box, resampled and wobbled a little, laid out left to right as the pad would record them.
 */
object InkGlyphs {

    private fun ring(cx: Float, cy: Float, rx: Float, ry: Float, from: Double = -PI / 2) =
        (0..16).map { i ->
            val a = from + 2 * PI * i / 16
            cx + rx * cos(a).toFloat() to cy + ry * sin(a).toFloat()
        }

    private val glyphs: Map<Char, List<List<Pair<Float, Float>>>> = mapOf(
        '0' to listOf(ring(0.3f, 0.5f, 0.27f, 0.5f)),
        '1' to listOf(listOf(0.12f to 0.2f, 0.32f to 0f, 0.32f to 1f)),
        '2' to listOf(listOf(0.05f to 0.25f, 0.15f to 0.05f, 0.35f to 0f, 0.52f to 0.1f, 0.55f to 0.3f,
            0.45f to 0.5f, 0.05f to 1f, 0.6f to 1f)),
        '3' to listOf(listOf(0.05f to 0.1f, 0.3f to 0f, 0.5f to 0.1f, 0.52f to 0.3f, 0.3f to 0.48f,
            0.52f to 0.62f, 0.55f to 0.85f, 0.35f to 1f, 0.05f to 0.92f)),
        '4' to listOf(listOf(0.42f to 0f, 0.05f to 0.65f, 0.6f to 0.65f), listOf(0.45f to 0.3f, 0.45f to 1f)),
        '5' to listOf(listOf(0.55f to 0f, 0.1f to 0f, 0.07f to 0.45f, 0.3f to 0.38f, 0.5f to 0.45f,
            0.57f to 0.7f, 0.45f to 0.95f, 0.25f to 1f, 0.05f to 0.9f)),
        '6' to listOf(listOf(0.5f to 0.05f, 0.3f to 0f, 0.1f to 0.25f, 0.05f to 0.6f, 0.15f to 0.95f,
            0.35f to 1f, 0.52f to 0.85f, 0.5f to 0.6f, 0.3f to 0.5f, 0.1f to 0.6f)),
        '7' to listOf(listOf(0.05f to 0f, 0.6f to 0f, 0.25f to 1f)),
        '8' to listOf(ring(0.3f, 0.24f, 0.2f, 0.24f), ring(0.3f, 0.73f, 0.26f, 0.27f)),
        '9' to listOf(ring(0.3f, 0.28f, 0.25f, 0.28f, from = 0.0), listOf(0.55f to 0.28f, 0.5f to 1f)),
        '+' to listOf(listOf(0.05f to 0.5f, 0.55f to 0.5f), listOf(0.3f to 0.25f, 0.3f to 0.75f)),
        '-' to listOf(listOf(0.05f to 0.5f, 0.55f to 0.5f)),
        '*' to listOf(listOf(0.1f to 0.3f, 0.5f to 0.7f), listOf(0.5f to 0.3f, 0.1f to 0.7f)),
        '=' to listOf(listOf(0.05f to 0.4f, 0.55f to 0.4f), listOf(0.05f to 0.62f, 0.55f to 0.62f)),
    )

    /**
     * [text] written as strokes ('*' is drawn as ×, '-' as a minus), [size] pad units tall,
     * starting at ([x], [y]). [seed] fixes the wobble so a test is repeatable.
     */
    fun write(text: String, x: Float = 0.1f, y: Float = 0.3f, size: Float = 0.12f, seed: Int = 7): List<InkStroke> {
        val random = Random(seed)
        val strokes = mutableListOf<InkStroke>()
        var left = x
        for (c in text) {
            if (c == ' ') { left += size * 0.4f; continue }
            val glyph = glyphs[c] ?: error("no glyph for '$c'")
            for (line in glyph) {
                val points = mutableListOf<InkPoint>()
                line.zipWithNext().forEach { (a, b) ->
                    for (i in 0 until 6) {
                        val t = i / 6f
                        points += InkPoint(
                            left + (a.first + (b.first - a.first) * t) * size + wobble(random, size),
                            y + (a.second + (b.second - a.second) * t) * size + wobble(random, size)
                        )
                    }
                }
                val end = line.last()
                points += InkPoint(left + end.first * size, y + end.second * size)
                strokes += InkStroke(points)
            }
            left += size * 0.8f
        }
        return strokes
    }

    private fun wobble(random: Random, size: Float) = (random.nextFloat() - 0.5f) * size * 0.04f
}
