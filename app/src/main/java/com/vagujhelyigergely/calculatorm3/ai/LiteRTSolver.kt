package com.vagujhelyigergely.calculatorm3.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * MathSolver backend using Google LiteRT-LM for .litertlm models (e.g. Gemma 3n).
 */
class LiteRTSolver : MathSolver {

    private val mutex = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null

    override val isModelLoaded: Boolean get() = engine != null && conversation != null

    override suspend fun loadModel(modelPath: String) =
        withContext(Dispatchers.IO) {
            release()
            val config = EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = Backend.GPU(),
            )
            val eng = Engine(config)
            eng.initialize()

            val conv = eng.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        Content.Text(SolverPrompts.SYSTEM_PROMPT)
                    )
                )
            )
            engine = eng
            conversation = conv
        }

    override suspend fun solveFromImageStreaming(
        imagePath: String,
        onToken: suspend (partialRaw: String) -> Unit
    ): Result<RecognitionResult> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val conv = conversation
                ?: return@withContext Result.failure(IllegalStateException("Model not loaded"))
            try {
                val rawBuilder = StringBuilder()
                conv.sendMessageAsync(
                    Contents.of(
                        Content.ImageFile(imagePath),
                        Content.Text(SolverPrompts.USER_PROMPT)
                    )
                ).collect { message ->
                    val text = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    rawBuilder.append(text)
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
            }
        }
    }

    override suspend fun release() {
        mutex.withLock {
            conversation?.close()
            conversation = null
            engine?.close()
            engine = null
        }
    }
}
