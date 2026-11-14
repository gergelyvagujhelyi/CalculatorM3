package com.vagujhelyigergely.calculatorm3.ai

/** What the AI screens need from the answer translator ([AnswerTranslator]; tests fake it). */
interface TextTranslator {
    /** [pack] is downloaded and verified. */
    fun isInstalled(pack: TranslationPack): Boolean

    /** Downloads [pack] (removing any other language's), reporting downloaded/total bytes. */
    suspend fun download(pack: TranslationPack, onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> })

    /** Removes every downloaded pack. */
    suspend fun deleteAll()

    /** Translates each of [texts] (Markdown lines with maths) into [pack]'s language. */
    suspend fun translate(texts: List<String>, pack: TranslationPack): List<String>

    /** Frees the loaded model's memory. */
    suspend fun release()
}
