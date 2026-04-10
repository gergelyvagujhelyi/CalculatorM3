package com.vagujhelyigergely.calculatorm3.ai

data class RecognitionResult(val raw: String, val answer: String)

class RecognitionException(message: String, val rawResponse: String) : Exception(message)

/**
 * Common interface for AI backends that solve handwritten math from images.
 */
interface MathSolver {
    val isModelLoaded: Boolean

    suspend fun loadModel(modelPath: String, mmprojPath: String?)
    suspend fun solveFromImageStreaming(
        imagePath: String,
        onToken: suspend (partialRaw: String) -> Unit
    ): Result<RecognitionResult>
    fun release()
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

    /** Extract the last number from an LLM response (the final answer). */
    fun extractAnswer(raw: String): String {
        val numberPattern = Regex("-?\\d+\\.?\\d*")
        val matches = numberPattern.findAll(raw).toList()
        return matches.lastOrNull()?.value ?: ""
    }
}
