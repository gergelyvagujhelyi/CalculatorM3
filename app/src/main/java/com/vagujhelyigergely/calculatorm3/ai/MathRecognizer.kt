package com.vagujhelyigergely.calculatorm3.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level API for recognizing and solving handwritten math expressions
 * from images using a local vision LLM via NobodyWho.
 */
data class RecognitionResult(val raw: String, val answer: String)

class MathRecognizer {

    private var modelHandle: Long = 0L
    private var chatHandle: Long = 0L

    val isNativeLibraryAvailable: Boolean get() = NobodyWhoBridge.isAvailable
    val isModelLoaded: Boolean get() = modelHandle != 0L && chatHandle != 0L

    /**
     * Load the vision model and create a chat session.
     * Runs on IO dispatcher.
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
     * Send an image to the vision model, stream tokens via [onToken],
     * and return the numerical answer extracted from the response.
     */
    suspend fun solveFromImageStreaming(
        imagePath: String,
        onToken: suspend (partialRaw: String) -> Unit
    ): Result<RecognitionResult> = withContext(Dispatchers.IO) {
        if (!isModelLoaded) {
            return@withContext Result.failure(IllegalStateException("Model not loaded"))
        }
        var streamHandle = 0L
        try {
            streamHandle = NobodyWhoBridge.startAskWithImage(
                chatHandle, USER_PROMPT, imagePath
            )
            val rawBuilder = StringBuilder()
            while (true) {
                val token = NobodyWhoBridge.nextToken(streamHandle) ?: break
                rawBuilder.append(token)
                onToken(rawBuilder.toString())
            }
            val raw = rawBuilder.toString()
            val answer = extractAnswer(raw)
            if (answer.isBlank()) {
                Result.failure(Exception("Could not extract a numerical answer"))
            } else {
                Result.success(RecognitionResult(raw = raw, answer = answer))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (streamHandle != 0L) {
                NobodyWhoBridge.freeTokenStream(streamHandle)
            }
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
     * Extract the numerical answer from the LLM response.
     * Looks for the last number in the response (the final answer).
     */
    private fun extractAnswer(raw: String): String {
        // Match numbers including negatives and decimals (e.g. -3.14, 42, 0.5)
        val numberPattern = Regex("-?\\d+\\.?\\d*")
        val matches = numberPattern.findAll(raw).toList()
        // The last number in the response is typically the answer
        return matches.lastOrNull()?.value ?: ""
    }

    companion object {
        private const val CONTEXT_SIZE = 4096

        private const val SYSTEM_PROMPT =
            "You are a math solver. When shown an image of a handwritten mathematical " +
            "expression, identify the expression and calculate the answer. " +
            "Show your work briefly: state the expression you see, then calculate " +
            "step by step, and give the final numerical answer on the last line. " +
            "The final line must contain only the number."

        private const val USER_PROMPT =
            "Calculate the result of the mathematical expression in this image."
    }
}
