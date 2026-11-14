package com.vagujhelyigergely.calculatorm3.ai

/**
 * The native translator (slimt, built from src/main/cpp) that runs Mozilla's Firefox Translations
 * models. Use it through [AnswerTranslator]; this is only the raw JNI surface.
 */
internal object SlimtNative {
    init {
        System.loadLibrary("answertranslator")
    }

    /** Loads one language pair. Returns a handle, or 0 on failure (see [lastError]). */
    external fun load(model: String, vocabulary: String, targetVocabulary: String?, shortlist: String?): Long

    /** Translates each string, which must be a single sentence. Null on failure. */
    external fun translate(handle: Long, sentences: Array<String>): Array<String>?

    external fun release(handle: Long)

    external fun lastError(): String
}
