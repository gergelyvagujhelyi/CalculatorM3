package com.vagujhelyigergely.calculatorm3.ai

import java.text.BreakIterator
import java.util.Locale

/**
 * Prepares English answer text for a sentence translator and puts the result back together,
 * so that only words get translated:
 * - each line keeps its list marker ("1. ", "- ") and its line break;
 * - a line is split into sentences, since the translator takes one sentence at a time;
 * - maths is swapped for placeholders (MATH1, MATH2…) before translation and restored after:
 *   first all of it (TeX such as $…$ and plain expressions such as `8 × 6 = 48`), then, for a
 *   sentence that didn't come back intact, only the TeX;
 * - a sentence that still lost or changed a number or a placeholder stays in English, so the
 *   working shown never differs from what the model wrote.
 */
object TranslationText {

    private fun placeholder(i: Int) = "MATH$i"

    private val marker = Regex("^(\\s*(?:\\d{1,2}[.)]|[-*•])\\s+)")

    // TeX in $…$, $$…$$, \(…\), \[…\].
    private val tex = Regex("\\$\\$.+?\\$\\$|\\$[^$\\n]+\\$|\\\\\\(.+?\\\\\\)|\\\\\\[.+?\\\\]")

    // Numbers or single-letter variables joined by at least one operator.
    private const val OPERAND = "(?:\\d+(?:[.,]\\d+)*%?|[a-zA-Z](?![a-zA-Z]))"
    private val expression = Regex(
        "(?<![\\p{L}\\d])\\(*\\s*$OPERAND(?:\\s*\\)*\\s*[-+*/×÷^=·≈<>≤≥−]\\s*\\(*\\s*$OPERAND)+\\s*\\)*"
    )
    private val number = Regex("\\d+(?:[.,]\\d+)*")
    private val anyPlaceholder = Regex("MATH\\d+")

    private class Masked(val text: String, val math: List<String>)

    /**
     * Translates [texts] with [translateSentences], which gets a list of single sentences and
     * must return one translation for each, in order (it's called at most twice). [joiner] goes
     * between the translated sentences of a line: a space, or nothing for Chinese and Japanese.
     */
    fun translate(
        texts: List<String>,
        joiner: String = " ",
        translateSentences: (List<String>) -> List<String>,
    ): List<String> {
        // texts → lines → (marker, sentences)
        val prepared = texts.map { text ->
            text.split("\n").map { line ->
                val prefix = marker.find(line)?.value.orEmpty()
                prefix to sentences(line.substring(prefix.length))
            }
        }
        val english = prepared.flatMap { lines -> lines.flatMap { it.second } }

        // null = not translated (yet).
        val result = arrayOfNulls<String>(english.size)
        for (withExpressions in listOf(true, false)) {
            val todo = english.indices.filter { result[it] == null }
            if (todo.isEmpty()) break
            val masked = todo.map { mask(english[it], withExpressions) }
            val translated = translateSentences(masked.map { it.text })
            require(translated.size == masked.size) { "Expected ${masked.size} translations, got ${translated.size}" }
            todo.forEachIndexed { k, i -> result[i] = unmask(masked[k], translated[k]) }
        }

        var next = 0
        return prepared.map { lines ->
            lines.joinToString("\n") { (prefix, sentences) ->
                val parts = sentences.map { sentence ->
                    val translated = result[next++]
                    (translated ?: sentence) to (translated == null)
                }
                prefix + parts.mapIndexed { k, (text, kept) ->
                    when {
                        k == 0 -> text
                        // An English sentence needs its space even in Chinese or Japanese.
                        joiner.isEmpty() && (kept || parts[k - 1].second) -> " $text"
                        else -> joiner + text
                    }
                }.joinToString("")
            }
        }
    }

    private fun sentences(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        // Shield the maths from the sentence splitter: "0.25" or "x = 4. Then" isn't split inside.
        val shielded = mask(body, withExpressions = true)
        val iterator = BreakIterator.getSentenceInstance(Locale.US)
        iterator.setText(shielded.text)
        val out = mutableListOf<String>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val piece = shielded.text.substring(start, end).trim()
            if (piece.isNotEmpty()) out += restore(piece, shielded.math)
            start = end
            end = iterator.next()
        }
        return out
    }

    private fun mask(text: String, withExpressions: Boolean): Masked {
        val math = mutableListOf<String>()
        fun swap(s: String, regex: Regex) = regex.replace(s) { m ->
            // Keep the spaces around the maths outside the placeholder.
            val value = m.value
            val lead = value.takeWhile { it == ' ' }
            val trail = value.takeLastWhile { it == ' ' }
            math += value.trim()
            lead + placeholder(math.size) + trail
        }
        val withoutTex = swap(text, tex)
        return Masked(if (withExpressions) swap(withoutTex, expression) else withoutTex, math)
    }

    private fun restore(text: String, math: List<String>): String =
        anyPlaceholder.replace(text) { m ->
            val index = m.value.removePrefix("MATH").toInt()
            math.getOrNull(index - 1) ?: m.value
        }

    /** The translation with its maths restored, or null if it didn't come back intact. */
    private fun unmask(masked: Masked, translated: String): String? {
        if (translated.isBlank()) return null
        val placeholders = anyPlaceholder.findAll(translated).map { it.value }.sorted().toList()
        val expected = masked.math.indices.map { placeholder(it + 1) }.sorted()
        if (placeholders != expected) return null
        // The numbers outside the maths must survive as they were, e.g. "40 euros".
        if (numbers(masked.text) != numbers(translated)) return null
        return restore(translated, masked.math)
    }

    private fun numbers(text: String): List<String> =
        number.findAll(anyPlaceholder.replace(text, " ")).map { it.value.replace(",", ".") }.sorted().toList()
}
