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

    /**
     * Extract the numerical answer from the LLM response. The model is prompted to put only
     * the number on the last line, so that case is trusted first; the remaining steps degrade
     * gracefully for messier output (an `= 42` tail, an aside like `17 (12+5)`, `1,234`)
     * instead of blindly grabbing the last trailing token.
     */
    fun extractAnswer(raw: String): String {
        val cleaned = raw.replace(thousandsSeparator, "")
        val lastLine = cleaned.trimEnd().lines().lastOrNull { it.isNotBlank() }?.trim() ?: ""

        // 1) Last line is exactly a number (what the system prompt asks for) — highest confidence.
        numberPattern.matchEntire(lastLine)?.let { return it.value }

        // 2) The number right after the last '=' ("x = 42", "= 17 (i.e. 12+5)").
        val afterEquals = lastLine.substringAfterLast('=', "")
        if (afterEquals.isNotEmpty() && afterEquals.length < lastLine.length) {
            numberPattern.find(afterEquals)?.let { return it.value }
        }

        // 3) A single number on the last line is unambiguous; with several, the stated result
        //    usually leads and asides like "17 (12+5)" follow, so prefer the first.
        val lastLineNumbers = numberPattern.findAll(lastLine).toList()
        if (lastLineNumbers.isNotEmpty()) return lastLineNumbers.first().value

        // 4) Nothing on the last line — fall back to the last number anywhere.
        return numberPattern.findAll(cleaned).lastOrNull()?.value ?: ""
    }
}
