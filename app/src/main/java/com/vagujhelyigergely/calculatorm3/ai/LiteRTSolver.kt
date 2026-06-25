package com.vagujhelyigergely.calculatorm3.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * MathSolver backend using Google LiteRT-LM for .litertlm models.
 *
 * Vision uses the GPU backend where available, but some devices have an
 * incompatible GPU/driver and throw (JNI/native) when the GPU vision backend
 * initializes. We try GPU vision first and fall back to CPU vision — both at
 * load time and once more on the first inference failure.
 */
class LiteRTSolver(private val context: Context) : MathSolver {

    private val mutex = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var currentModelPath: String? = null
    @Volatile private var backendLabel: String = "—"

    /** TEMP debug: short reason the GPU/NPU backend failed to load (null on success). */
    @Volatile override var lastGpuError: String? = null
        private set

    override val isModelLoaded: Boolean get() = engine != null
    override val activeBackend: String get() = backendLabel

    override suspend fun loadModel(modelPath: String) =
        withContext(Dispatchers.IO) {
            closeEngine()
            currentModelPath = modelPath
            // TEMP: NPU-compiled bundles carry the vendor (e.g. "qualcomm") in the filename.
            initEngine(modelPath, tryNpu = modelPath.contains("qualcomm", ignoreCase = true))
        }

    /** Backend ladder: NPU (if requested) → GPU → CPU. */
    private fun initEngine(modelPath: String, tryNpu: Boolean) {
        if (tryNpu) {
            try {
                engine = buildEngine(
                    modelPath,
                    backend = Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir),
                    visionBackend = Backend.GPU()
                )
                backendLabel = "NPU"
                lastGpuError = null
                return
            } catch (e: Exception) {
                lastGpuError = "NPU: " + (e.message ?: e.toString()).replace('\n', ' ').take(160)
                Log.w(TAG, "NPU backend failed, trying GPU", e)
            }
        }
        try {
            engine = buildEngine(modelPath, Backend.GPU(), Backend.GPU())
            backendLabel = "GPU"
            if (!tryNpu) lastGpuError = null
            return
        } catch (e: Exception) {
            lastGpuError = (lastGpuError?.plus(" | ") ?: "") + "GPU: " +
                (e.message ?: e.toString()).replace('\n', ' ').take(160)
            Log.w(TAG, "GPU backend failed, falling back to CPU", e)
        }
        engine = buildEngine(modelPath, Backend.CPU(), Backend.CPU())
        backendLabel = "CPU"
    }

    private fun buildEngine(modelPath: String, backend: Backend, visionBackend: Backend): Engine =
        Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = visionBackend,
            )
        ).also { it.initialize() }

    private fun createConversation(): Conversation {
        val eng = engine ?: throw IllegalStateException("Model not loaded")
        try { conversation?.close() } catch (_: Exception) {}
        return eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(Content.Text(SolverPrompts.SYSTEM_PROMPT)),
                samplerConfig = SamplerConfig(
                    topK = SolverPrompts.TOP_K,
                    topP = SolverPrompts.TOP_P.toDouble(),
                    temperature = SolverPrompts.TEMPERATURE.toDouble()
                )
            )
        ).also { conversation = it }
    }

    private suspend fun runInference(
        imagePath: String,
        onToken: suspend (partialRaw: String) -> Unit
    ): Result<RecognitionResult> {
        val conv = createConversation()
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
        return if (answer.isBlank()) {
            Result.failure(RecognitionException("Could not extract a numerical answer", raw))
        } else {
            Result.success(RecognitionResult(raw = raw, answer = answer))
        }
    }

    override suspend fun solveFromImageStreaming(
        imagePath: String,
        onToken: suspend (partialRaw: String) -> Unit
    ): Result<RecognitionResult> = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                runInference(imagePath, onToken)
            } catch (e: Throwable) {
                Log.e(TAG, "LiteRT inference failed", e)
                // If GPU was active, reload on CPU and retry once — some devices
                // initialize the GPU backend fine but fault during inference.
                val path = currentModelPath
                if (backendLabel != "CPU" && path != null) {
                    Log.w(TAG, "Reloading on CPU and retrying once")
                    try {
                        closeEngine()
                        engine = buildEngine(path, Backend.CPU(), Backend.CPU())
                        backendLabel = "CPU"
                        return@withContext runInference(imagePath, onToken)
                    } catch (retry: Throwable) {
                        Log.e(TAG, "CPU retry also failed", retry)
                        return@withContext Result.failure(retry.asException())
                    }
                }
                Result.failure(e.asException())
            }
        }
    }

    private fun closeEngine() {
        try { conversation?.close() } catch (_: Exception) {}
        conversation = null
        try { engine?.close() } catch (_: Exception) {}
        engine = null
    }

    override suspend fun release() {
        mutex.withLock { closeEngine() }
    }

    companion object {
        private const val TAG = "LiteRTSolver"

        /** Wrap [Throwable] in an [Exception] so callers expecting Exception types are satisfied. */
        private fun Throwable.asException(): Exception =
            this as? Exception ?: RuntimeException("Inference failed", this)
    }
}
