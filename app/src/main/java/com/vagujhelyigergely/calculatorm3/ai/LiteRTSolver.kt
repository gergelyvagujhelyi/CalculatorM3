package com.vagujhelyigergely.calculatorm3.ai

import android.os.SystemClock
import android.util.Log
import com.vagujhelyigergely.calculatorm3.R
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * MathSolver backend using Google LiteRT-LM for .litertlm models.
 *
 * Vision uses the GPU backend where available, but some devices have an
 * incompatible GPU/driver and throw (JNI/native) when the GPU vision backend
 * initializes. We try GPU vision first and fall back to CPU vision — both at
 * load time and once more on the first inference failure.
 */
class LiteRTSolver : MathSolver {

    private val mutex = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var currentModelPath: String? = null
    @Volatile private var backendLabel: String = "—"

    override val isModelLoaded: Boolean get() = engine != null
    override val activeBackend: String get() = backendLabel

    override suspend fun loadModel(modelPath: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            closeEngine()
            currentModelPath = modelPath
            initEngine(modelPath)
        }
    }

    /** Backend ladder: GPU → CPU. */
    private fun initEngine(modelPath: String) {
        try {
            engine = buildEngine(modelPath, Backend.GPU(), Backend.GPU())
            backendLabel = "GPU"
            return
        } catch (e: Exception) {
            Log.w(TAG, "GPU backend failed, falling back to CPU", e)
        }
        engine = buildEngine(modelPath, Backend.CPU(), Backend.CPU())
        backendLabel = "CPU"
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
    ): Result<RecognitionResult> = coroutineScope {
        val conv = createConversation()
        val rawBuilder = StringBuilder()
        var tokenCount = 0
        var capReached = false
        // Safeguard state, shared with the watchdog. elapsedRealtime() is monotonic (immune to
        // wall-clock changes). Atomics because the watchdog reads them from another thread.
        val startMs = SystemClock.elapsedRealtime()
        val lastActivityMs = AtomicLong(startMs)
        val firstTokenSeen = AtomicBoolean(false)
        val timeoutKind = AtomicReference<TimeoutKind?>(null)

        // The token stream is collected in a CHILD job so the watchdog can cancel it on a stall.
        // cancelProcess() alone does NOT complete the litertlm flow (verified: the scan hangs on
        // "Processing"), so a stuck collect can only be unwound by cancelling its coroutine.
        val collectJob = launch {
            try {
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
                    lastActivityMs.set(SystemClock.elapsedRealtime())
                    firstTokenSeen.set(true)
                    onToken(rawBuilder.toString())
                    // Hard output cap (length): a runaway that never emits a stop would stream
                    // forever. Interrupt native generation and throw to unwind the collect now.
                    if (++tokenCount >= MAX_OUTPUT_TOKENS) {
                        capReached = true
                        Log.w(TAG, "Output cap reached ($tokenCount tokens) — stopping generation")
                        try { conv.cancelProcess() } catch (_: Exception) {}
                        throw OutputCapReached()
                    }
                }
            } catch (e: OutputCapReached) {
                // Cap hit — collectJob completes normally; capReached drives the failure below.
            }
        }

        // Watchdog (time): trips on a too-long prefill (no first token yet), an inter-token stall,
        // or the absolute wall-clock ceiling. It records WHY, signals native to stop, and cancels
        // the collect. Runs on Default so a busy IO thread can't starve the deadline checks.
        val watchdog = launch(Dispatchers.Default) {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val started = firstTokenSeen.get()
                val tripped = when {
                    now - startMs >= TOTAL_BUDGET_MS -> TimeoutKind.TOTAL
                    now - lastActivityMs.get() >=
                        (if (started) INACTIVITY_BUDGET_MS else PREFILL_BUDGET_MS) ->
                        if (started) TimeoutKind.STALL else TimeoutKind.PREFILL
                    else -> null
                }
                if (tripped != null) {
                    timeoutKind.set(tripped)
                    Log.w(TAG, "watchdog: $tripped budget exceeded — stopping generation")
                    try { conv.cancelProcess() } catch (_: Exception) {}
                    collectJob.cancel()
                    break
                }
            }
        }

        // join() returns whether the collect finished normally, hit the cap, or was cancelled by
        // the watchdog (a cancelled child does not fail the scope). Genuine user/background
        // cancellation cancels this whole coroutineScope instead, propagating a CancellationException
        // out of runInference — handled as before by solveFromImageStreaming.
        collectJob.join()
        watchdog.cancel()

        timeoutKind.get()?.let { kind ->
            return@coroutineScope Result.failure(RecognitionException(kind.message, rawBuilder.toString()))
        }
        if (capReached) {
            return@coroutineScope Result.failure(
                RecognitionException(
                    "The answer was cut off before the model finished — it ran past the length " +
                        "limit. Please try again.",
                    rawBuilder.toString()
                )
            )
        }
        val raw = rawBuilder.toString()
        val answer = SolverPrompts.extractAnswer(raw)
        if (answer.isBlank()) {
            Result.failure(RecognitionException(
                "Could not extract a numerical answer", raw, R.string.error_no_numerical_answer))
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
                        if (retry is kotlinx.coroutines.CancellationException) throw retry
                        Log.e(TAG, "CPU retry also failed", retry)
                        return@withContext Result.failure(retry.asException())
                    }
                }
                Result.failure(e.asException())
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

        /** Internal signal thrown to unwind token streaming the moment the output cap is hit. */
        private class OutputCapReached : Exception()

        /**
         * Hard ceiling on generated chunks (≈ tokens) per scan — a safety net against a runaway
         * generation that never emits a stop (it would otherwise stream forever and pin the GPU).
         * Deliberately set well ABOVE any legitimate answer so it never truncates a hard question:
         * a math solution with steps is a few hundred tokens at most, and this is near the model's
         * own KV-cache ceiling. Because it's a token (not time) cap, it cuts off at the same amount
         * of content regardless of device speed — a fast phone just reaches it sooner in wall-time.
         * Tune here if answers ever get truncated.
         */
        private const val MAX_OUTPUT_TOKENS = 2048

        /** Why the watchdog aborted a generation, with the (English) message shown to the user.
         *  The app's other AI-scan errors are also hardcoded English; localizing these is a
         *  separate cross-layer change (the solver has no Context). */
        private enum class TimeoutKind(val message: String) {
            PREFILL("The model got stuck before producing an answer. Please try again."),
            STALL("The answer stopped midway through. Please try again."),
            TOTAL("The scan took too long and was stopped. Please try again with a clearer photo."),
        }

        /** First-token (prefill) budget. Vision prefill is long — ~25s+ on a Galaxy Note 9 — so it
         *  gets its own generous allowance, separate from inter-token stalls. */
        private const val PREFILL_BUDGET_MS = 60_000L

        /** Max gap between two decoded tokens once decoding has started. Decode runs ~2–10 tok/s
         *  (gaps of 100–500ms), so 20s only trips on a genuine native stall, not slow-but-alive. */
        private const val INACTIVITY_BUDGET_MS = 20_000L

        /** Absolute wall-clock ceiling for one generation (prefill + decode). Bounds the
         *  pathological case; a healthy math answer finishes well inside this. */
        private const val TOTAL_BUDGET_MS = 120_000L

        /** How often the watchdog re-evaluates the deadlines. */
        private const val WATCHDOG_TICK_MS = 1_000L

        /** Wrap [Throwable] in an [Exception] so callers expecting Exception types are satisfied. */
        private fun Throwable.asException(): Exception =
            this as? Exception ?: RuntimeException("Inference failed", this)
    }
}
