package com.vagujhelyigergely.calculatorm3.ai

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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * MathSolver backend using Google LiteRT-LM for .litertlm models.
 *
 * Backend ladder: NPU → GPU → CPU. The NPU rung is attempted only when [npuLibraryDir] is set (the
 * app's native-library dir, where LiteRT's NPU "dispatch" delegate looks for the vendor runtime) and an
 * NPU-compiled model is passed to [loadModel]; NPU then runs the language model while the vision encoder
 * stays on the GPU (the encoder isn't NPU-compiled upstream). Some devices initialize a backend fine but
 * fault during the first inference, so we step down the same ladder once more at inference time.
 *
 * @param npuLibraryDir directory the NPU dispatch delegate scans for vendor libraries
 *   (`context.applicationInfo.nativeLibraryDir`), or null to disable the NPU rung entirely.
 */
class LiteRTSolver(private val npuLibraryDir: String? = null) : MathSolver {

    private val mutex = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    /** Baseline model for the GPU/CPU rungs. */
    @Volatile private var currentModelPath: String? = null
    /** NPU-compiled artifact for the NPU rung, or null when no NPU model is loaded. */
    @Volatile private var currentNpuPath: String? = null
    @Volatile private var backendLabel: String = "—"

    /** TEMP debug: short reason the NPU backend failed (null on success / when not attempted). */
    @Volatile override var lastNpuError: String? = null
        private set

    override val isModelLoaded: Boolean get() = engine != null
    override val activeBackend: String get() = backendLabel

    override suspend fun loadModel(modelPath: String, npuModelPath: String?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            closeEngine()
            currentModelPath = modelPath
            // Only attempt NPU when we have both a dispatch-library dir and an NPU-compiled model.
            currentNpuPath = npuModelPath?.takeIf { npuLibraryDir != null }
            initEngine()
        }
    }

    /** Backend ladder: NPU → GPU → CPU (NPU rung only when an NPU model + library dir are present). */
    private fun initEngine() {
        val gpuCpuPath = currentModelPath ?: throw IllegalStateException("No model path")
        val npuPath = currentNpuPath
        val npuDir = npuLibraryDir
        if (npuPath != null && npuDir != null) {
            try {
                // Hybrid: language model on the NPU, vision encoder on the GPU (NPU vision isn't compiled).
                // KNOWN LIMITATION: the per-SoC NPU artifact has a small, fixed image-token budget — it
                // oversimplifies the image and can answer wrong (fast but inaccurate). This is the model, not
                // our handoff: GPU feeds the identical preprocessed image and reads it fine. Don't "fix" it in
                // preprocessing — see NPU-FINDINGS.md §6. NPU is opt-in / off-by-default for this reason.
                engine = buildEngine(npuPath, Backend.NPU(npuDir), Backend.GPU())
                backendLabel = "NPU"
                lastNpuError = null
                return
            } catch (e: Exception) {
                lastNpuError = "NPU: " + (e.message ?: e.toString()).replace('\n', ' ').take(200)
                Log.w(TAG, "NPU backend failed, falling back to GPU", e)
            }
        }
        try {
            engine = buildEngine(gpuCpuPath, Backend.GPU(), Backend.GPU())
            backendLabel = "GPU"
            return
        } catch (e: Exception) {
            Log.w(TAG, "GPU backend failed, falling back to CPU", e)
        }
        engine = buildEngine(gpuCpuPath, Backend.CPU(), Backend.CPU())
        backendLabel = "CPU"
    }

    /** Backends below [current] in the NPU→GPU→CPU ladder, for the inference-time step-down retry. */
    private fun lowerRungs(current: String): List<Triple<String, () -> Backend, () -> Backend>> = when (current) {
        "NPU" -> listOf(
            Triple("GPU", { Backend.GPU() }, { Backend.GPU() }),
            Triple("CPU", { Backend.CPU() }, { Backend.CPU() }),
        )
        "GPU" -> listOf(Triple("CPU", { Backend.CPU() }, { Backend.CPU() }))
        else -> emptyList()
    }

    private fun buildEngine(modelPath: String, backend: Backend, visionBackend: Backend): Engine {
        val eng = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = visionBackend,
                // We send exactly one image per turn (see runInference), so cap image
                // buffers at 1 rather than the engine default. maxNumTokens (the KV-cache
                // cap) is left at default: on-device measurement showed the model weights,
                // not the KV cache, dominate memory — so capping it wouldn't move the needle.
                maxNumImages = 1,
            )
        )
        try {
            eng.initialize()
        } catch (e: Throwable) {
            // initialize() can fault (e.g. GPU driver) after the native Engine was
            // constructed; close it so we don't leak native memory before fallback.
            try { eng.close() } catch (_: Exception) {}
            throw e
        }
        return eng
    }

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
                // Never swallow cancellation — let the coroutine actually cancel
                // instead of treating it as a failure and reloading on CPU.
                if (e is kotlinx.coroutines.CancellationException) throw e
                // A stop() interrupts native generation via cancelProcess(), which surfaces here as
                // a native exception (not CancellationException) while the coroutine is being
                // cancelled. Treat that as cancellation so we skip the expensive GPU→CPU reload+retry
                // that would just be discarded at the next suspension point.
                if (!isActive) throw kotlinx.coroutines.CancellationException("Inference cancelled", e)
                Log.e(TAG, "LiteRT inference failed", e)
                // Some devices initialize a backend fine but fault during inference. Step down the
                // remaining ladder (NPU→GPU→CPU), reloading the baseline model and retrying once per
                // rung. For a GPU-active load this reduces to the original GPU→CPU retry.
                val gpuCpuPath = currentModelPath
                var lastError: Throwable = e
                if (gpuCpuPath != null) {
                    for (rung in lowerRungs(backendLabel)) {
                        Log.w(TAG, "Reloading on ${rung.first} and retrying once")
                        try {
                            closeEngine()
                            engine = buildEngine(gpuCpuPath, rung.second(), rung.third())
                            backendLabel = rung.first
                            return@withContext runInference(imagePath, onToken)
                        } catch (retry: Throwable) {
                            if (retry is kotlinx.coroutines.CancellationException) throw retry
                            Log.e(TAG, "${rung.first} retry also failed", retry)
                            lastError = retry
                        }
                    }
                }
                Result.failure(lastError.asException())
            }
        }
    }

    override fun cancel() {
        // Signal native generation to stop. Deliberately does NOT take [mutex]: the lock is held
        // by the in-flight solveFromImageStreaming, and cancelProcess() is a thread-safe signal to
        // the native side (not a state mutation), so read the @Volatile conversation directly.
        try {
            conversation?.cancelProcess()
        } catch (e: Exception) {
            Log.w(TAG, "cancelProcess() failed", e)
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
