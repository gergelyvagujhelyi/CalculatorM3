package com.vagujhelyigergely.calculatorm3.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MathSolver backend using NobodyWho (Rust/llama.cpp) for GGUF models.
 */
class NobodyWhoSolver : MathSolver {

    private var modelHandle: Long = 0L
    private var chatHandle: Long = 0L

    val isNativeLibraryAvailable: Boolean get() = NobodyWhoBridge.isAvailable
    override val isModelLoaded: Boolean get() = modelHandle != 0L && chatHandle != 0L

    override suspend fun loadModel(modelPath: String, mmprojPath: String?) =
        withContext(Dispatchers.IO) {
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
                SolverPrompts.SYSTEM_PROMPT,
                CONTEXT_SIZE
            )
        }

    override suspend fun solveFromImageStreaming(
        imagePath: String,
        onToken: suspend (partialRaw: String) -> Unit
    ): Result<RecognitionResult> = withContext(Dispatchers.IO) {
        if (!isModelLoaded) {
            return@withContext Result.failure(IllegalStateException("Model not loaded"))
        }
        var streamHandle = 0L
        try {
            streamHandle = NobodyWhoBridge.startAskWithImage(
                chatHandle, SolverPrompts.USER_PROMPT, imagePath
            )
            val rawBuilder = StringBuilder()
            while (true) {
                val token = NobodyWhoBridge.nextToken(streamHandle) ?: break
                rawBuilder.append(token)
                onToken(rawBuilder.toString())
            }
            val raw = rawBuilder.toString()
            val answer = SolverPrompts.extractAnswer(raw)
            if (answer.isBlank()) {
                Result.failure(RecognitionException("Could not extract a numerical answer", raw))
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

    override fun release() {
        if (chatHandle != 0L) {
            NobodyWhoBridge.freeChat(chatHandle)
            chatHandle = 0L
        }
        if (modelHandle != 0L) {
            NobodyWhoBridge.freeModel(modelHandle)
            modelHandle = 0L
        }
    }

    companion object {
        private const val CONTEXT_SIZE = 4096
    }
}
