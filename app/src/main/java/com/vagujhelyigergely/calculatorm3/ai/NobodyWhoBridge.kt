package com.vagujhelyigergely.calculatorm3.ai

/**
 * JNI bridge to the nobodywho-android native library.
 *
 * All methods are blocking and must be called from a background thread
 * (e.g. Dispatchers.IO).
 *
 * The native library may not be present (it must be cross-compiled from Rust).
 * Check [isAvailable] before calling any external methods.
 */
object NobodyWhoBridge {

    val isAvailable: Boolean

    init {
        isAvailable = try {
            System.loadLibrary("nobodywho_android")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * Load a GGUF model with an optional vision projection model.
     * @return opaque native handle (pass to other methods)
     */
    external fun loadModel(modelPath: String, useGpu: Boolean, mmprojPath: String?): Long

    /** Free a model handle returned by [loadModel]. */
    external fun freeModel(handle: Long)

    /**
     * Create a chat session from a loaded model.
     * @return opaque native handle
     */
    external fun createChat(modelHandle: Long, systemPrompt: String?, contextSize: Int): Long

    /** Free a chat handle returned by [createChat]. */
    external fun freeChat(handle: Long)

    /**
     * Send a text-only prompt and block until the full response is ready.
     * @return the model's complete response
     */
    external fun ask(chatHandle: Long, textPrompt: String): String

    /**
     * Send a multimodal prompt (text + image path) and block until the full response is ready.
     * @return the model's complete response
     */
    external fun askWithImage(chatHandle: Long, textPrompt: String, imagePath: String): String

    /**
     * Start a multimodal prompt and return a token stream handle for polling tokens.
     * @return opaque stream handle
     */
    external fun startAskWithImage(chatHandle: Long, textPrompt: String, imagePath: String): Long

    /**
     * Get the next token from a stream. Blocks until available.
     * @return the next token, or null when the stream is finished
     */
    external fun nextToken(streamHandle: Long): String?

    /** Free a token stream handle returned by [startAskWithImage]. */
    external fun freeTokenStream(handle: Long)
}
