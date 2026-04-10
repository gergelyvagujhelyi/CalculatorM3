package com.vagujhelyigergely.calculatorm3.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level API for recognizing handwritten math expressions from images
 * using a local vision LLM via NobodyWho.
 */
class MathRecognizer {

    private var modelHandle: Long = 0L
    private var chatHandle: Long = 0L

    val isNativeLibraryAvailable: Boolean get() = NobodyWhoBridge.isAvailable
    val isModelLoaded: Boolean get() = modelHandle != 0L && chatHandle != 0L

    /**
     * Load the vision model and create a chat session.
     * Must be called before [recognizeExpression]. Runs on IO dispatcher.
     */
    suspend fun loadModel(modelPath: String, mmprojPath: String) = withContext(Dispatchers.IO) {
        if (!NobodyWhoBridge.isAvailable) {
            throw IllegalStateException(
                "Native library not available. Build libnobodywho_android.so " +
                "and place it in app/src/main/jniLibs/arm64-v8a/"
            )
        }
        release()
        modelHandle = NobodyWhoBridge.loadModel(modelPath, true, mmprojPath)
        chatHandle = NobodyWhoBridge.createChat(
            modelHandle,
            SYSTEM_PROMPT,
            CONTEXT_SIZE
        )
    }

    /**
     * Send an image to the vision model and return the recognized math expression.
     * The result is post-processed to be compatible with the calculator's input format.
     */
    suspend fun recognizeExpression(imagePath: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (!isModelLoaded) {
                return@withContext Result.failure(IllegalStateException("Model not loaded"))
            }
            try {
                val raw = NobodyWhoBridge.askWithImage(
                    chatHandle,
                    USER_PROMPT,
                    imagePath
                )
                val cleaned = postProcess(raw)
                if (cleaned.isBlank()) {
                    Result.failure(Exception("Could not recognize a math expression"))
                } else {
                    Result.success(cleaned)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Free native resources. Safe to call multiple times. */
    fun release() {
        if (chatHandle != 0L) {
            NobodyWhoBridge.freeChat(chatHandle)
            chatHandle = 0L
        }
        if (modelHandle != 0L) {
            NobodyWhoBridge.freeModel(modelHandle)
            modelHandle = 0L
        }
    }

    /**
     * Clean up the LLM response into a calculator-compatible expression.
     * Maps common math symbols to the ones used by the calculator.
     */
    private fun postProcess(raw: String): String {
        var expr = raw.trim()

        // Strip markdown code fences or backticks the model might add
        expr = expr.removePrefix("```").removeSuffix("```").trim()
        expr = expr.removePrefix("`").removeSuffix("`").trim()

        // Strip any leading/trailing quotes
        if (expr.startsWith("\"") && expr.endsWith("\"")) {
            expr = expr.substring(1, expr.length - 1)
        }

        // Map standard math notation to calculator symbols
        expr = expr.replace("×", "×")  // keep multiplication sign
            .replace("*", "×")
            .replace("÷", "÷")         // keep division sign
            .replace("/", "÷")
            .replace("−", "−")         // keep minus sign
            .replace("-", "−")
            .replace("pi", "π")
            .replace("PI", "π")
            .replace("sqrt", "√")

        // Remove any characters not supported by the calculator
        val allowed = setOf(
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '+', '−', '×', '÷', '.', '(', ')', '^', '!', '%', 'π', '√',
            ' '
        )
        expr = expr.filter { it in allowed }

        // Remove spaces
        expr = expr.replace(" ", "")

        return expr
    }

    companion object {
        private const val CONTEXT_SIZE = 4096

        private const val SYSTEM_PROMPT =
            "You are a math expression recognizer. When shown an image of a handwritten " +
            "mathematical expression, respond with ONLY the expression using standard " +
            "mathematical notation. Use: + for addition, - for subtraction, * for " +
            "multiplication, / for division, ^ for exponentiation, sqrt for square root, " +
            "! for factorial, pi for pi. Use parentheses where needed. " +
            "Do not include any explanation, just the expression."

        private const val USER_PROMPT = "What mathematical expression is written in this image?"
    }
}
