package com.vagujhelyigergely.calculatorm3

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.DownloadAuthException
import com.vagujhelyigergely.calculatorm3.ai.DownloadStorageException
import com.vagujhelyigergely.calculatorm3.ai.MathSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.RecognitionResult
import com.vagujhelyigergely.calculatorm3.ai.RetryableHttpException
import com.vagujhelyigergely.calculatorm3.auth.HuggingFaceAuthManager
import com.vagujhelyigergely.calculatorm3.camera.ScanViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * On-device integration checks for behaviour the JVM tests can only cover in pieces: real
 * HttpURLConnection downloads against a local fake server, the real SharedPreferences token
 * migration, the packaged backup rules, and real JPEG decoding with EXIF orientation.
 *
 * These write app data (prefs, files under the models folder, cache images), so they run only
 * with `allowDataWrites=true`: on CI's throwaway emulator, or on a phone with the isolated
 * `-PdeviceTest` build (package ….devicetest), never against a personal phone's real app.
 */
class DeviceIntegrationTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = AiModel.entries.first { !it.requiresAuth }
    private lateinit var modelManager: ModelManager

    @Before
    fun setUp() {
        assumeTrue(
            "Writes app data; run with allowDataWrites=true (CI emulator or the -PdeviceTest build)",
            InstrumentationRegistry.getArguments().getString("allowDataWrites") == "true"
        )
        modelManager = ModelManager(context)
    }

    // ── Local fake HTTP server ──────────────────────────────────────────────

    /** One canned HTTP response; [bodyBytes] may be shorter than [contentLength] (truncation). */
    private class Response(
        val status: String,
        val headers: Map<String, String> = emptyMap(),
        val bodyBytes: ByteArray = ByteArray(0),
        val contentLength: Long = bodyBytes.size.toLong(),
        /** If set, send this many body bytes, wait for [resume], then send the rest. */
        val pauseAfter: Int? = null,
        val resume: CountDownLatch? = null
    )

    /** Serves one request per connection on 127.0.0.1, recording each request's headers. */
    private class FakeServer(private val respond: (Map<String, String>) -> Response) : AutoCloseable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val requests = mutableListOf<Map<String, String>>()
        val url get() = "http://127.0.0.1:${socket.localPort}/model.bin"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val client: Socket = try { socket.accept() } catch (_: IOException) { break }
                    client.use { handle(it) }
                }
            }
        }

        private fun handle(client: Socket) {
            val reader = client.getInputStream().bufferedReader()
            reader.readLine() ?: return // request line
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            synchronized(requests) { requests += headers }
            val r = respond(headers)
            val out = client.getOutputStream()
            val head = buildString {
                append("HTTP/1.1 ${r.status}\r\n")
                append("Content-Length: ${r.contentLength}\r\n")
                append("Connection: close\r\n")
                r.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                append("\r\n")
            }
            out.write(head.toByteArray())
            if (r.pauseAfter != null && r.resume != null) {
                out.write(r.bodyBytes, 0, r.pauseAfter)
                out.flush()
                r.resume.await(30, TimeUnit.SECONDS)
                try {
                    out.write(r.bodyBytes, r.pauseAfter, r.bodyBytes.size - r.pauseAfter)
                } catch (_: IOException) {
                    return // the client hung up (a cancelled download), as it should
                }
            } else {
                out.write(r.bodyBytes)
            }
            out.flush()
        }

        override fun close() = socket.close()
    }

    private val payload = ByteArray(200_000) { (it * 31 % 251).toByte() }
    private val fileName = "devicetest-download.bin"
    private fun destFile() = File(File(context.getExternalFilesDir(null), "models/${model.id}"), fileName)
    private fun tmpFile() = File(destFile().path + ".tmp")

    @After
    fun cleanUp() {
        if (::modelManager.isInitialized) {
            destFile().delete(); tmpFile().delete()
        }
    }

    private fun download(url: String) = runBlocking { modelManager.downloadFile(model, url, fileName) { _, _ -> } }

    private inline fun <reified T : Throwable> downloadExpecting(url: String): T {
        try {
            download(url)
        } catch (e: Throwable) {
            if (e is T) return e
            fail("expected ${T::class.simpleName}, got ${e::class.simpleName}: ${e.message}")
        }
        fail("expected ${T::class.simpleName}, but the download succeeded")
        throw IllegalStateException()
    }

    // ── Downloads (#84) ─────────────────────────────────────────────────────

    @Test
    fun completeDownloadWritesTheFile() {
        FakeServer { Response("200 OK", bodyBytes = payload) }.use { server ->
            download(server.url)
            assertArrayEquals(payload, destFile().readBytes())
            assertFalse(tmpFile().exists())
        }
    }

    @Test
    fun serviceUnavailableIsRetryableAndCarriesRetryAfter() {
        FakeServer { Response("503 Service Unavailable", mapOf("Retry-After" to "120")) }.use { server ->
            val e = downloadExpecting<RetryableHttpException>(server.url)
            assertEquals(503, e.httpCode)
            assertEquals(120_000L, e.retryAfterMs)
        }
    }

    @Test
    fun tooManyRequestsIsRetryable() {
        FakeServer { Response("429 Too Many Requests") }.use { server ->
            val e = downloadExpecting<RetryableHttpException>(server.url)
            assertEquals(429, e.httpCode)
            assertNull(e.retryAfterMs)
        }
    }

    @Test
    fun notFoundIsAPermanentFailureNotARetry() {
        FakeServer { Response("404 Not Found") }.use { server ->
            val e = downloadExpecting<Exception>(server.url)
            assertFalse("404 must not be retried", e is IOException)
            assertFalse(e is DownloadStorageException)
        }
    }

    @Test
    fun unauthorizedRoutesToSignIn() {
        FakeServer { Response("401 Unauthorized") }.use { server ->
            assertEquals(401, downloadExpecting<DownloadAuthException>(server.url).httpCode)
        }
    }

    @Test
    fun partialFileResumesWithARangeRequest() {
        val already = 80_000
        tmpFile().parentFile!!.mkdirs()
        tmpFile().writeBytes(payload.copyOfRange(0, already))
        FakeServer { headers ->
            assertEquals("bytes=$already-", headers["range"])
            Response("206 Partial Content",
                mapOf("Content-Range" to "bytes $already-${payload.size - 1}/${payload.size}"),
                payload.copyOfRange(already, payload.size))
        }.use { server ->
            download(server.url)
            assertEquals(1, server.requests.size)
            assertArrayEquals(payload, destFile().readBytes())
        }
    }

    @Test
    fun serverIgnoringRangeReplacesTheStalePartialFile() {
        tmpFile().parentFile!!.mkdirs()
        tmpFile().writeBytes(ByteArray(50_000) { 7 }) // stale bytes that must not survive
        FakeServer { Response("200 OK", bodyBytes = payload) }.use { server ->
            download(server.url)
            assertArrayEquals(payload, destFile().readBytes())
        }
    }

    @Test
    fun fileLargerThanTheDiskFailsUpFrontWithoutWriting() {
        // Announce 50 TB; the free-space check must refuse before any byte is written.
        FakeServer { Response("200 OK", contentLength = 50L * 1024 * 1024 * 1024 * 1024) }.use { server ->
            val e = downloadExpecting<DownloadStorageException>(server.url)
            assertTrue(e.message!!.contains("storage", ignoreCase = true))
            assertFalse(destFile().exists())
            assertTrue(!tmpFile().exists() || tmpFile().length() == 0L)
        }
    }

    @Test
    fun cutOffDownloadIsRetryableAndKeepsThePartialFile() {
        val sent = 60_000
        FakeServer { Response("200 OK", bodyBytes = payload.copyOfRange(0, sent), contentLength = payload.size.toLong()) }
            .use { server ->
                val e = downloadExpecting<IOException>(server.url)
                assertFalse(e is RetryableHttpException)
                assertFalse(destFile().exists())
                assertEquals(sent.toLong(), tmpFile().length())
            }
    }

    @Test
    fun cancelledDownloadStopsWritingAndARestartProducesAnIntactFile() {
        val stalled = 64 * 1024
        val releaseStalled = CountDownLatch(1)
        val connections = AtomicInteger()
        FakeServer { headers ->
            if (connections.incrementAndGet() == 1) {
                // First attempt: send 64 KB, then stall mid-body (a slow or hung connection).
                Response("200 OK", bodyBytes = payload, pauseAfter = stalled, resume = releaseStalled)
            } else {
                val from = headers["range"]!!.removePrefix("bytes=").removeSuffix("-").toInt()
                Response("206 Partial Content",
                    mapOf("Content-Range" to "bytes $from-${payload.size - 1}/${payload.size}"),
                    payload.copyOfRange(from, payload.size))
            }
        }.use { server ->
            val first = CoroutineScope(Dispatchers.IO).launch {
                modelManager.downloadFile(model, server.url, fileName) { _, _ -> }
            }
            val deadline = System.currentTimeMillis() + 10_000
            while (tmpFile().length() < stalled && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals(stalled.toLong(), tmpFile().length())

            // Cancel while it's blocked reading: it must actually stop, not linger in the read.
            first.cancel()
            val stopped = runBlocking { withTimeoutOrNull(5_000) { first.join() } } != null
            // Let the stalled connection send the rest; a still-running first attempt would write it.
            releaseStalled.countDown()
            assertTrue("cancelled download was still blocked in a read 5 s after cancel", stopped)

            download(server.url) // the replacement resumes from the partial file
            assertArrayEquals("model file corrupted by the cancelled attempt", payload, destFile().readBytes())
        }
    }

    @Test
    fun downloadRequestNeedsUnmeteredNetworkUnlessMobileDataWasAllowed() {
        assertEquals(NetworkType.UNMETERED,
            modelManager.downloadRequest(model, allowMeteredNetwork = false).workSpec.constraints.requiredNetworkType)
        assertEquals(NetworkType.CONNECTED,
            modelManager.downloadRequest(model, allowMeteredNetwork = true).workSpec.constraints.requiredNetworkType)
    }

    // ── Hugging Face login storage (#91) ────────────────────────────────────

    @Test
    fun legacyTokenMovesToItsOwnPreferencesFile() {
        val aiSettings = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)
        val auth = context.getSharedPreferences(ModelManager.AUTH_PREFS, Context.MODE_PRIVATE)
        auth.edit().clear().commit()
        aiSettings.edit().putString("hf_token", "legacy-token").commit()
        try {
            assertEquals("legacy-token", ModelManager(context).hfToken)
            assertEquals("legacy-token", auth.getString("hf_token", null))
            assertNull("token must leave the transferable ai_settings file", aiSettings.getString("hf_token", null))

            ModelManager(context).hfToken = "new-token"
            assertEquals("new-token", auth.getString("hf_token", null))
            assertNull(aiSettings.getString("hf_token", null))
        } finally {
            auth.edit().clear().commit()
            aiSettings.edit().remove("hf_token").commit()
        }
    }

    @Test
    fun backupAndTransferRulesExcludeExactlyTheLoginFile() {
        val loginFile = "${ModelManager.AUTH_PREFS}.xml"
        for (res in listOf(R.xml.data_extraction_rules, R.xml.backup_rules)) {
            val excludes = mutableListOf<Pair<String?, String?>>()
            val parser = context.resources.getXml(res)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "exclude") {
                    excludes += parser.getAttributeValue(null, "domain") to parser.getAttributeValue(null, "path")
                }
            }
            assertTrue("no excludes in ${context.resources.getResourceEntryName(res)}", excludes.isNotEmpty())
            excludes.forEach { (domain, path) ->
                assertEquals("sharedpref", domain)
                assertEquals(loginFile, path)
            }
        }
    }

    // ── Photo rotation (androidx ExifInterface) ─────────────────────────────

    private class NoopSolver : MathSolver {
        override val isModelLoaded = false
        override val activeBackend = "—"
        override suspend fun loadModel(modelPath: String) = Unit
        override suspend fun solveFromImageStreaming(
            imagePath: String, onToken: suspend (partialRaw: String) -> Unit
        ): Result<RecognitionResult> = Result.failure(UnsupportedOperationException())
        override suspend fun release() = Unit
    }

    /** Writes a [width]×[height] JPEG tagged with EXIF [orientation]; returns its path. */
    private fun photo(width: Int, height: Int, orientation: Int): String {
        val file = File(context.cacheDir, "devicetest_${System.nanoTime()}.jpg")
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file.path
    }

    private fun sizeOf(path: String) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeFile(path, it) }.let { it.outWidth to it.outHeight }

    private fun prepared(width: Int, height: Int, orientation: Int, maxEdge: Int = 768): Pair<Int, Int> {
        val vm = ScanViewModel(NoopSolver(), modelManager, HuggingFaceAuthManager(context), context)
        val source = photo(width, height, orientation)
        val out = vm.prepareImage(source, maxEdge)
        assertNotNull("prepareImage failed", out)
        return sizeOf(out!!).also { File(out).delete(); File(source).delete() }
    }

    @Test
    fun photoTaggedRotate90IsTurnedUpright() {
        assertEquals(200 to 400, prepared(400, 200, ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(200 to 400, prepared(400, 200, ExifInterface.ORIENTATION_ROTATE_270))
    }

    @Test
    fun photoTaggedNormalOr180KeepsItsShape() {
        assertEquals(400 to 200, prepared(400, 200, ExifInterface.ORIENTATION_NORMAL))
        assertEquals(400 to 200, prepared(400, 200, ExifInterface.ORIENTATION_ROTATE_180))
    }

    @Test
    fun largeRotatedPhotoIsDownscaledToTheMaxEdge() {
        val (w, h) = prepared(3000, 1500, ExifInterface.ORIENTATION_ROTATE_90, maxEdge = 768)
        assertEquals(768, maxOf(w, h))
        assertTrue("still portrait after rotation", h > w)
    }
}
