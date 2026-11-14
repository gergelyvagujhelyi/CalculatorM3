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

    /**
     * Solve the photo at [imagePath], calling [onToken] with the full output so far as it
     * streams. An empty string means the output restarts from scratch (e.g. a GPU→CPU retry).
     */
    suspend fun solveFromImageStreaming(
        imagePath: String,
        onToken: suspend (partialRaw: String) -> Unit
    ): Result<RecognitionResult>

    /**
     * Solve a typed [problem] step by step ([SolverPrompts.STEPS_SYSTEM_PROMPT]). Succeeds with
     * the raw response and an empty answer: the caller parses it ([SolverPrompts.parseSteps]),
     * since an answer such as `x = 5` is a fine result here, not a failure.
     */
    suspend fun solveFromTextStreaming(
        problem: String,
        onToken: suspend (partialRaw: String) -> Unit
    ): Result<RecognitionResult> = Result.failure(UnsupportedOperationException("No text solving"))

    /**
     * Interrupt an in-flight [solveFromImageStreaming] so native generation stops promptly.
     * Must be safe to call WITHOUT holding the backend's internal lock — the running inference
     * already holds it, and this is only a signal to the native side, not a state change.
     * No-op by default for backends without a hard stop.
     */
    fun cancel() {}

    /**
     * Image-token budget for the next [loadModel] (null = the engine default). It's fixed when
     * the model loads, so a change takes effect on the next load. No-op for solvers without it.
     */
    fun setVisualTokenBudget(budget: Int?) {}

    suspend fun release()
}

/**
 * A typed problem's parsed step-by-step answer ([SolverPrompts.parseSteps]).
 * [problem]: how the model restated it. [steps]: the working, as Markdown. [answer]: the final
 * answer as written (may be `x = 5` or a fraction). [usableAnswer]: the number for "Use", or ""
 * when the answer isn't a single number that agrees with the working.
 */
data class SolvedSteps(
    val problem: String,
    val steps: String,
    val answer: String,
    val usableAnswer: String,
)

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

    /** For typed problems: a fixed shape that [parseSteps] reads back. */
    const val STEPS_SYSTEM_PROMPT =
        "You are a math tutor. Solve the user's math problem step by step.\n" +
        "Reply in exactly this format and nothing else:\n" +
        "Problem: <the problem, restated in one line>\n" +
        "1. <first step>\n" +
        "2. <next step>\n" +
        "(at most 4 steps, one short line each)\n" +
        "Answer: <only the final answer, e.g. 42 or x = 5; a number without units where possible>"

    private val labelDecoration = Regex("[*_#]+")
    private val problemLabel = Regex("^problem\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val answerLabel = Regex("^(final\\s+)?answer\\s*:\\s*", RegexOption.IGNORE_CASE)

    /**
     * Read a [STEPS_SYSTEM_PROMPT] response, or null if it has no `Answer:` line (the caller then
     * shows the raw output to check). Labels may carry Markdown emphasis (`**Answer:**`). With
     * no `Problem:` line the problem is [typed]. The answer is offered for "Use" only if it's a
     * plain number (no variable, no '=') that [extractAnswer] accepts against the working.
     */
    fun parseSteps(raw: String, typed: String): SolvedSteps? {
        val lines = raw.trim().lines()
        fun label(line: String) = line.replace(labelDecoration, "").trim()
        val answerIndex = lines.indexOfLast { answerLabel.containsMatchIn(label(it)) }
        if (answerIndex < 0) return null
        val answer = label(lines[answerIndex]).replace(answerLabel, "").trim()
        if (answer.isEmpty()) return null
        val problemIndex = lines.indexOfFirst { problemLabel.containsMatchIn(label(it)) }
            .takeIf { it in 0 until answerIndex }
        val problem = problemIndex?.let { label(lines[it]).replace(problemLabel, "").trim() }
            ?.takeIf { it.isNotEmpty() } ?: typed.trim()
        val stepLines = lines.subList((problemIndex ?: -1) + 1, answerIndex)
        val steps = stepLines.joinToString("\n").trim()
        return SolvedSteps(problem, steps, answer, usableAnswer(stepLines, answer))
    }

    private val answerWrappers = Regex("\\\\boxed\\{([^{}]*)\\}")
    private val answerDelimiters = Regex("\\\\[()\\[\\]]|\\$")

    private fun usableAnswer(working: List<String>, answer: String): String {
        val bare = answer.replace(answerWrappers, "$1").replace(answerDelimiters, "").trim()
        // "x = 5", "5/4", "60 km/h", "\frac{1}{2}": shown, but not a number to put in the calculator.
        // (An 'e'/'E' is allowed only as exponent notation, which extractAnswer reads.)
        val letters = bare.replace(Regex("(?<=\\d)[eE](?=[+-]?\\d)"), "")
        if ('=' in bare || letters.any { it.isLetter() }) return ""
        return extractAnswer((working + bare).joinToString("\n"))
    }

    // Sampler params for the LiteRT-LM conversation (match AI Edge Gallery defaults).
    const val TOP_K = 64
    const val TOP_P = 0.95f
    const val TEMPERATURE = 1.0f

    private val numberPattern = Regex("-?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
    // English thousands separators ("1,234" -> "1234"): a comma between a digit and exactly
    // three digits that aren't themselves followed by another digit.
    private val thousandsSeparator = Regex("(?<=\\d),(?=\\d{3}(?:\\D|\$))")
    // A comma right after a lone 0 ("0,125", "-0,5") can only be a decimal comma: no number
    // written with thousands separators starts with a 0 group. Read it as a decimal point
    // before thousands separators are stripped, which would otherwise turn 0,125 into 125.
    private val decimalCommaAfterZero = Regex("(?<![\\d.,])0,(?=\\d)")

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
     * separators, a decimal comma after a lone 0 (`0,125`), markdown/LaTeX wrappers, a trailing sentence period, a `= 42` tail and asides
     * like `17 (that is 12 + 5)`.
     *
     * The answer must also agree with the result the model already wrote in its working (see
     * [agreesWithWorking]): a final line of `25` after `1 ÷ 4 = 0.25` is rejected.
     */
    fun extractAnswer(raw: String): String {
        val normalized = raw
            .replace(minusVariants, "-")
            .replace(dashAsSign, "-")
            .replace(dashAsPunctuation, " ")
            .replace(boxed, "$1")
            .replace(latexDelimiters, "")
            .replace(timesTenPower, "$1E$2")
            .replace(decimalCommaAfterZero, "0.")
            .replace(thousandsSeparator, "")

        // The answer line is the last line with a digit, so a trailing sign-off ("Hope this
        // helps!") is skipped. Earlier lines (the working) are never mined for a fallback.
        val lines = normalized.lines()
        val answerIndex = lines.indexOfLast { line -> line.any { it.isDigit() } }
        if (answerIndex < 0) return ""
        val answerLine = lines[answerIndex].trim()

        // With an '=', the answer is what follows the last one ("2 + 3 = 5", "x = 17 (12 + 5)").
        val candidateText = answerLine.substringAfterLast('=')

        val standalone = numberPattern.findAll(candidateText)
            .filter { isStandalone(candidateText, it.range, lineHasEquals = '=' in answerLine) }
            .toList()
        // Exactly one complete number, or nothing: "4 or 5" and "1,5" are ambiguous.
        val match = standalone.singleOrNull() ?: return ""
        // "5." is the end of a sentence, not part of the number.
        val answer = match.value.removeSuffix(".")
        // An answer line with its own '=' ("12 + 21 = 33") states the result itself; a bare
        // final line is checked against the working above it.
        if ('=' in answerLine) return answer
        return if (agreesWithWorking(lines.subList(0, answerIndex), answer)) answer else ""
    }

    /**
     * True unless the working before a bare answer line contradicts [answer]. The model's own
     * result is what follows the last '=' in the working ("1 ÷ 4 = 1/4 or 0.25" gives 0.25;
     * fraction pieces are skipped as above), plus any number stated after that line ("then add
     * 12 to get 33"). If there is such a result and none of them equals [answer], the final line
     * is a slip — measured on a Note 9: the working said `= 0.25`, the final line `25` — and
     * offering it would put a wrong number into "Use answer".
     *
     * Working without such a result (no '=', or only fractions/percentages after it) can't be
     * checked, so it's accepted as before.
     */
    private fun agreesWithWorking(working: List<String>, answer: String): Boolean {
        val resultIndex = working.indexOfLast { line ->
            '=' in line && line.substringAfterLast('=').any { it.isDigit() }
        }
        if (resultIndex < 0) return true
        val rhs = working[resultIndex].substringAfterLast('=')
        val results = standaloneNumbers(rhs, lineHasEquals = true) +
            working.drop(resultIndex + 1).flatMap { standaloneNumbers(it, lineHasEquals = '=' in it) }
        if (results.isEmpty()) return true
        return results.any { sameValue(it, answer) }
    }

    private fun standaloneNumbers(text: String, lineHasEquals: Boolean): List<String> =
        numberPattern.findAll(text)
            .filter { isStandalone(text, it.range, lineHasEquals) }
            .map { it.value.removeSuffix(".") }
            .toList()

    /**
     * Numerically equal ("0.25" = ".25", "14" = "14.0"), or one is the other rounded or cut off to
     * its own number of decimals ("0.3333" for 0.333333…, "0.6667" or "0.6666" for 0.666666…).
     * The shorter one needs at least two decimals for that, so "0" doesn't count as 0.25 rounded.
     */
    private fun sameValue(a: String, b: String): Boolean {
        val x = a.toBigDecimalOrNull() ?: return false
        val y = b.toBigDecimalOrNull() ?: return false
        if (x.compareTo(y) == 0) return true
        val (short, long) = if (x.scale() <= y.scale()) x to y else y to x
        if (short.scale() < 2) return false
        return long.setScale(short.scale(), java.math.RoundingMode.HALF_UP).compareTo(short) == 0 ||
            long.setScale(short.scale(), java.math.RoundingMode.DOWN).compareTo(short) == 0
    }

    /** True if the number at [range] isn't an operand of an operator or LaTeX group. */
    private fun isStandalone(text: String, range: IntRange, lineHasEquals: Boolean): Boolean {
        val before = text.substring(0, range.first).trimEnd().lastOrNull()
        val after = text.substring(range.last + 1).trimStart().firstOrNull()
        if (before != null && before in OPERAND_NEIGHBORS) return false
        if (after != null && after in OPERAND_NEIGHBORS &&
            !isSentenceExclamation(text, range, lineHasEquals)
        ) return false
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

    /**
     * "The answer is 42!": a '!' that ends a sentence of prose is punctuation, not a factorial.
     * Only when the '!' is glued to the number and followed by the end of the line or a new
     * word, the line has no '=', and words come before the number. Math such as "= 5!", a bare
     * "5!", "3!!" or "4! + 1" stays a factorial, so it's rejected rather than read as 5, 3 or 4.
     */
    private fun isSentenceExclamation(text: String, range: IntRange, lineHasEquals: Boolean): Boolean {
        if (lineHasEquals) return false
        val bang = range.last + 1
        if (bang >= text.length || text[bang] != '!') return false
        val next = text.getOrNull(bang + 1)
        if (next != null && !next.isWhitespace()) return false // "5!3", "3!!"
        val rest = text.substring(bang + 1).trimStart()
        if (rest.isNotEmpty() && !rest.first().isLetter()) return false // "4! + 1"
        return text.substring(0, range.first).any { it.isLetter() } // prose before the number
    }
}
