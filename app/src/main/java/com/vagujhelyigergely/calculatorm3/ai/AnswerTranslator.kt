package com.vagujhelyigergely.calculatorm3.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * Translates the wording of the AI's English answers with Mozilla's Firefox Translations models,
 * entirely on the phone. Each language needs its [TranslationPack] downloaded once (15–38 MB);
 * after that it works offline, in well under a second per answer.
 *
 * Only words are translated: the maths ($…$ and expressions like `8 × 6 = 48`) is taken out
 * before translation and put back after, and a sentence whose numbers don't survive translation
 * stays in English (see [TranslationText]).
 */
class AnswerTranslator(context: Context) : TextTranslator {

    private val root = File(context.filesDir, "translation")
    private val mutex = Mutex()
    private var loadedCode: String? = null
    private var handle = 0L
    private var engine: ExecutorCoroutineDispatcher? = null

    fun packDir(pack: TranslationPack) = File(root, pack.code)

    private fun file(pack: TranslationPack, kind: PackFile.Kind) = File(packDir(pack), kind.fileName)

    private fun readyMarker(pack: TranslationPack) = File(packDir(pack), READY)

    /** The pack is fully downloaded and was verified. */
    override fun isInstalled(pack: TranslationPack): Boolean =
        readyMarker(pack).exists() && pack.files.all { file(pack, it.kind).length() == it.rawSize }

    /**
     * Downloads and verifies [pack], reporting downloaded bytes of [TranslationPack.downloadSize].
     * Other languages' packs are removed: only one language is ever used.
     */
    override suspend fun download(pack: TranslationPack, onProgress: (downloaded: Long, total: Long) -> Unit) {
        withContext(Dispatchers.IO) {
            if (isInstalled(pack)) return@withContext
            root.listFiles()?.filter { it.name != pack.code }?.forEach { it.deleteRecursively() }
            val dir = packDir(pack).apply { mkdirs() }
            readyMarker(pack).delete()
            var done = 0L
            for (part in pack.files) {
                val target = file(pack, part.kind)
                if (target.length() == part.rawSize && sha256(target) == part.sha256) {
                    done += part.size
                    onProgress(done, pack.downloadSize)
                    continue
                }
                val partial = File(dir, part.kind.fileName + ".part")
                val connection = URL("${TranslationPacks.BASE_URL}/${part.path}").openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("HTTP ${connection.responseCode} for ${part.path}")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val counting = CountingInputStream(connection.inputStream) { read ->
                        onProgress(done + read, pack.downloadSize)
                    }
                    GZIPInputStream(counting, BUFFER).use { input ->
                        partial.outputStream().buffered(BUFFER).use { output ->
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    val hash = digest.digest().toHex()
                    if (partial.length() != part.rawSize || hash != part.sha256) {
                        partial.delete()
                        throw IOException("Translation file failed its check: ${part.path}")
                    }
                    if (!partial.renameTo(target)) throw IOException("Couldn't save ${target.name}")
                    done += part.size
                    onProgress(done, pack.downloadSize)
                } finally {
                    connection.disconnect()
                    partial.delete()
                }
            }
            readyMarker(pack).writeText(pack.files.joinToString("\n") { it.sha256 })
        }
    }

    /** Removes every downloaded pack (and frees the loaded one). */
    override suspend fun deleteAll() {
        release()
        withContext(Dispatchers.IO) { root.deleteRecursively() }
    }

    /** Translates each of [texts] (any number of lines and sentences) into [pack]'s language. */
    override suspend fun translate(texts: List<String>, pack: TranslationPack): List<String> = onEngine(pack) { translator ->
        TranslationText.translate(texts, joiner(pack)) { sentences -> native(translator, sentences) }
    }

    /** The translator's own output for single [sentences], with no masking or checks. */
    internal suspend fun translateSentences(sentences: List<String>, pack: TranslationPack): List<String> =
        onEngine(pack) { translator -> native(translator, sentences) }

    /** Frees the loaded model's memory; the next translation loads it again (well under a second). */
    override suspend fun release() = mutex.withLock { unload() }

    /**
     * Runs [block] with [pack]'s model loaded, on that model's own thread. Each loaded model gets
     * a fresh thread, ended when the model is released: the native matrix library caches packed
     * weights per thread, keyed by memory address, so a thread that outlived its model could hand
     * a later model, mapped at the same address, the old model's weights (garbage output).
     * One thread per model also keeps that cache from being duplicated across threads.
     */
    private suspend fun <T> onEngine(pack: TranslationPack, block: (Long) -> T): T = mutex.withLock {
        if (loadedCode != pack.code || handle == 0L) {
            unload()
            check(isInstalled(pack)) { "The ${pack.languageName} translation isn't downloaded" }
            val thread = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "AnswerTranslator-${pack.code}").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            engine = thread
            handle = withContext(thread) { load(pack) }
            loadedCode = pack.code
        }
        val translator = handle
        withContext(checkNotNull(engine)) { block(translator) }
    }

    /** Releases the model on its thread, then ends the thread. Call with [mutex] held. */
    private suspend fun unload() {
        val thread = engine ?: return
        val loaded = handle
        handle = 0L
        loadedCode = null
        engine = null
        try {
            if (loaded != 0L) withContext(NonCancellable + thread) { SlimtNative.release(loaded) }
        } finally {
            thread.close()
        }
    }

    private fun native(translator: Long, sentences: List<String>): List<String> =
        SlimtNative.translate(translator, sentences.toTypedArray())?.toList()
            ?: throw IOException("Translation failed: ${SlimtNative.lastError()}")

    private fun load(pack: TranslationPack): Long {
        val started = System.currentTimeMillis()
        val target = pack.files.any { it.kind == PackFile.Kind.TARGET_VOCAB }
        val loaded = SlimtNative.load(
            file(pack, PackFile.Kind.MODEL).path,
            file(pack, PackFile.Kind.VOCAB).path,
            if (target) file(pack, PackFile.Kind.TARGET_VOCAB).path else null,
            file(pack, PackFile.Kind.SHORTLIST).path,
        )
        if (loaded == 0L) throw IOException("Couldn't load the translation: ${SlimtNative.lastError()}")
        Log.i(TAG, "Loaded ${pack.code} translation in ${System.currentTimeMillis() - started} ms")
        return loaded
    }

    private class CountingInputStream(
        private val inner: java.io.InputStream,
        private val onRead: (Long) -> Unit,
    ) : java.io.FilterInputStream(inner) {
        private var count = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) onRead(++count) }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) { count += it; onRead(count) } }
    }

    companion object {
        private const val TAG = "AnswerTranslator"
        private const val READY = "ready"
        private const val BUFFER = 64 * 1024

        /** Chinese and Japanese sentences follow each other without a space. */
        fun joiner(pack: TranslationPack) = if (pack.code == "zh" || pack.code == "ja") "" else " "

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(BUFFER).use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    }
}
