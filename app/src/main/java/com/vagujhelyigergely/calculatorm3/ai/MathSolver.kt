package com.vagujhelyigergely.calculatorm3.ai

data class RecognitionResult(val raw: String, val answer: String)

class RecognitionException(message: String, val rawResponse: String) : Exception(message)

/**
 * Common interface for AI backends that solve handwritten math from images.
 */
interface MathSolver {
    val isModelLoaded: Boolean

    suspend fun loadModel(modelPath: String)
    suspend fun solveFromImageStreaming(
        imagePath: String,
        onToken: suspend (partialRaw: String) -> Unit
    ): Result<RecognitionResult>
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

    // Sampler params for the LiteRT-LM conversation.
    const val TOP_K = 64
    const val TOP_P = 0.95f
    const val TEMPERATURE = 1.0f

    /** Extract the numerical answer from the LLM response.
     *  Checks the last non-empty line first (where the model is prompted to put the answer),
     *  then falls back to the last number in the full response. */
    fun extractAnswer(raw: String): String {
        val numberPattern = Regex("-?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
        // Try the last non-empty line first (system prompt tells model to put answer there)
        val lastLine = raw.trimEnd().lines().lastOrNull { it.isNotBlank() }?.trim() ?: ""
        val lastLineMatch = numberPattern.find(lastLine)
        if (lastLineMatch != null && lastLineMatch.value == lastLine) {
            // Last line is purely a number — high confidence answer
            return lastLineMatch.value
        }
        // Fall back to last number on the last line
        val lastLineNumbers = numberPattern.findAll(lastLine).toList()
        if (lastLineNumbers.isNotEmpty()) {
            return lastLineNumbers.last().value
        }
        // Final fallback: last number anywhere in the response
        val allNumbers = numberPattern.findAll(raw).toList()
        return allNumbers.lastOrNull()?.value ?: ""
    }
}
