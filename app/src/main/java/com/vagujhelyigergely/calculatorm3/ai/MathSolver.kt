package com.vagujhelyigergely.calculatorm3.ai

import androidx.annotation.StringRes

data class RecognitionResult(val raw: String, val answer: String)

/**
 * [messageRes], when non-zero, is a localized string resource the UI resolves (the solver has no
 * Context). [message] is the English fallback for callers that don't carry a resource id.
 */
class RecognitionException(
    message: String,
    val rawResponse: String,
    @StringRes val messageRes: Int = 0,
) : Exception(message)

/**
 * Common interface for AI backends that solve handwritten math from images.
 */
interface MathSolver {
    val isModelLoaded: Boolean

    /** Which compute backend the loaded model is running on ("GPU", "CPU", or "—"). */
    val activeBackend: String

    suspend fun loadModel(modelPath: String)
    suspend fun solveFromImageStreaming(
        imagePath: String,
        onToken: suspend (partialRaw: String) -> Unit
    ): Result<RecognitionResult>

    /**
     * Interrupt an in-flight [solveFromImageStreaming] so native generation stops promptly.
     * Must be safe to call WITHOUT holding the backend's internal lock — the running inference
     * already holds it, and this is only a signal to the native side, not a state change.
     * No-op by default for backends without a hard stop.
     */
    fun cancel() {}

    suspend fun release()
}

/** Shared prompts used by all backends. */
object SolverPrompts {
    const val SYSTEM_PROMPT =
        "You are a math solver. When shown an image of a handwritten mathematical " +
        "expression, identify the expression and calculate the answer. " +
        "Show your work briefly: state the expression you see, then calculate " +
        "step by step, and give the final numerical answer on the last line. " +
        "The final line must contain only the number."

    const val USER_PROMPT =
        "Calculate the result of the mathematical expression in this image."

    // Sampler params for the LiteRT-LM conversation (match AI Edge Gallery defaults).
    const val TOP_K = 64
    const val TOP_P = 0.95f
    const val TEMPERATURE = 1.0f

    private val numberPattern = Regex("-?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
    // English thousands separators ("1,234" -> "1234"): a comma between a digit and exactly
    // three digits that aren't themselves followed by another digit.
    private val thousandsSeparator = Regex("(?<=\\d),(?=\\d{3}(?:\\D|\$))")

    // Characters that are always a minus sign: U+2212 minus, hyphens, small/fullwidth hyphen-minus.
    private val minusVariants = Regex("[−‐‑﹣－]")
    // En/em dashes are prose punctuation ("42 — computed above") unless glued to a number ("–7").
    private val dashAsSign = Regex("[–—](?=\\.?\\d)")
    private val dashAsPunctuation = Regex("[–—]")
    // Wrappers around an otherwise plain number: **42**, \boxed{42}, $42$, \(42\), \[42\].
    private val boxed = Regex("\\\\boxed\\{([^{}]*)\\}")
    private val latexDelimiters = Regex("\\\\[()\\[\\]]|\\*\\*|\\$")
    // "a × 10^b" (also \times, \cdot, ·, *, x, and 10^{b}) → "aEb".
    private val timesTenPower =
        Regex("(-?(?:\\d+\\.?\\d*|\\.\\d+))\\s*(?:×|\\\\times|\\\\cdot|·|\\*|x)\\s*10\\^\\{?\\s*([+-]?\\d+)\\s*\\}?")
    // A number touching one of these is an operand of unfinished math or LaTeX (1/2, 2^10,
    // 12+5, 50%, √2, 2π, 5!, \frac{1}{2}), not a complete answer.
    private const val OPERAND_NEIGHBORS = "+-*/^×÷·%√π!{}\\_"

    /**
     * Extract the final numerical answer from the LLM response, or "" if there isn't exactly one
     * complete number to use. The model is prompted to end with a number-only line, but rather
     * than grab whatever digits are nearby, this only accepts a number that stands on its own:
     * a fragment of `1/2`, `2^10`, `12 + 5` or `\frac{1}{2}` is rejected instead of silently
     * becoming a different value. Rejection surfaces the raw response to the user, which is
     * safer than a wrong "Use answer".
     *
     * Supported: plain and E-notation numbers, Unicode minus signs, `a × 10^b`, English thousands
     * separators, markdown/LaTeX wrappers, a trailing sentence period, a `= 42` tail and asides
     * like `17 (that is 12 + 5)`.
     */
    fun extractAnswer(raw: String): String {
        val normalized = raw
            .replace(minusVariants, "-")
            .replace(dashAsSign, "-")
            .replace(dashAsPunctuation, " ")
            .replace(boxed, "$1")
            .replace(latexDelimiters, "")
            .replace(timesTenPower, "$1E$2")
            .replace(thousandsSeparator, "")

        // The answer line is the last line with a digit, so a trailing sign-off ("Hope this
        // helps!") is skipped. Earlier lines (the working) are never mined for a fallback.
        val answerLine = normalized.lines().lastOrNull { line -> line.any { it.isDigit() } }?.trim()
            ?: return ""

        // With an '=', the answer is what follows the last one ("2 + 3 = 5", "x = 17 (12 + 5)").
        val candidateText = answerLine.substringAfterLast('=')

        val standalone = numberPattern.findAll(candidateText)
            .filter { isStandalone(candidateText, it.range) }
            .toList()
        // Exactly one complete number, or nothing: "4 or 5" and "1,5" are ambiguous.
        val match = standalone.singleOrNull() ?: return ""
        // "5." is the end of a sentence, not part of the number.
        return match.value.removeSuffix(".")
    }

    /** True if the number at [range] isn't an operand of an operator or LaTeX group. */
    private fun isStandalone(text: String, range: IntRange): Boolean {
        val before = text.substring(0, range.first).trimEnd().lastOrNull()
        val after = text.substring(range.last + 1).trimStart().firstOrNull()
        if (before != null && before in OPERAND_NEIGHBORS) return false
        if (after != null && after in OPERAND_NEIGHBORS) return false
        // A leading '-' glued to a previous digit is a binary minus ("10-3"); the pattern
        // includes it in this match, so look one character further back.
        if (text[range.first] == '-' && before != null && (before.isDigit() || before == ')')) return false
        // The argument of a function call ("sqrt(2)", "ln(5)"): '(' glued to a name.
        if (before == '(') {
            val paren = text.lastIndexOf('(', range.first)
            if (paren > 0 && text[paren - 1].isLetter()) return false
        }
        // Digits glued to letters are identifiers or units without a space, not answers ("x2").
        if (before != null && before.isLetter() && range.first > 0 && !text[range.first - 1].isWhitespace()) return false
        return true
    }
}
