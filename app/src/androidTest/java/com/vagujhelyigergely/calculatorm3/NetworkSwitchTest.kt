package com.vagujhelyigergely.calculatorm3

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.ModelDownloadWorker
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The real WorkManager download on a real phone with Wi-Fi switched off and on (#84):
 * - without mobile-data consent, the download waits while only mobile data is available and
 *   starts once Wi-Fi is back;
 * - with consent, it runs over mobile data.
 *
 * Each case downloads only a few MB of a model that isn't installed, then cancels and deletes
 * the partial file. It toggles the device's Wi-Fi through the instrumentation shell and always
 * turns Wi-Fi back on. Runs only with `networkSwitch=true` and `allowDataWrites=true`, on a
 * device with a SIM and mobile data (not CI), using the isolated `-PdeviceTest` build.
 */
class NetworkSwitchTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val model = AiModel.GEMMA4_E4B
    private lateinit var manager: ModelManager
    private val workManager get() = WorkManager.getInstance(context)
    private val cm get() = context.getSystemService(ConnectivityManager::class.java)

    @Before
    fun setUp() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Toggles Wi-Fi and uses mobile data; pass networkSwitch=true and allowDataWrites=true",
            args.getString("networkSwitch") == "true" && args.getString("allowDataWrites") == "true")
        manager = ModelManager(context)
        assumeTrue("needs a model that isn't downloaded", !manager.areModelsAvailable(model))
        cleanUp()
    }

    @After
    fun tearDown() {
        if (!::manager.isInitialized) return
        manager.cancelDownload()
        wifi(on = true)
        waitUntil(60_000, "Wi-Fi back as the active network") { activeIsUnmetered() }
        Thread.sleep(2_000) // let a cancelled worker stop writing
        cleanUp()
    }

    private fun cleanUp() {
        File(context.getExternalFilesDir(null), "models/${model.id}").listFiles()?.forEach { it.delete() }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .use { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes() }
    }

    private fun wifi(on: Boolean) = shell("svc wifi ${if (on) "enable" else "disable"}")

    private fun activeCaps() = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    private fun activeIsUnmetered() =
        activeCaps()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    private fun activeIsMobile() = activeCaps()?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

    private fun state(): WorkInfo.State? =
        workManager.getWorkInfosForUniqueWork(ModelDownloadWorker.WORK_NAME).get().firstOrNull()?.state

    private fun partialBytes(): Long =
        File(context.getExternalFilesDir(null), "models/${model.id}/${model.files.first().filename}.tmp")
            .let { if (it.exists()) it.length() else 0L }

    private fun waitUntil(timeoutMs: Long, what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(1_000)
        }
        throw AssertionError("timed out after ${timeoutMs / 1000}s waiting for: $what")
    }

    private fun switchToMobileOnly() {
        wifi(on = false)
        waitUntil(60_000, "mobile data as the active network") { activeIsMobile() && !activeIsUnmetered() }
    }

    @Test
    fun withoutConsentTheDownloadWaitsForWifi() {
        switchToMobileOnly()
        manager.enqueueDownload(model, allowMeteredNetwork = false)

        // 40 s on mobile data only: the work must stay queued and download nothing.
        Thread.sleep(40_000)
        assertEquals(WorkInfo.State.ENQUEUED, state())
        assertEquals("no bytes may be fetched over mobile data", 0L, partialBytes())
        Log.i(TAG, "no consent: still ENQUEUED after 40 s on mobile, 0 bytes")

        wifi(on = true)
        waitUntil(120_000, "the download to start on Wi-Fi") {
            state() == WorkInfo.State.RUNNING && partialBytes() > 2L * 1024 * 1024
        }
        assertTrue(activeIsUnmetered())
        Log.i(TAG, "no consent: started on Wi-Fi, ${partialBytes() / 1024} KB")
    }

    @Test
    fun withConsentTheDownloadRunsOverMobileData() {
        switchToMobileOnly()
        manager.enqueueDownload(model, allowMeteredNetwork = true)

        waitUntil(120_000, "the download to run over mobile data") {
            assertFalse("Wi-Fi came back mid-test", activeIsUnmetered())
            state() == WorkInfo.State.RUNNING && partialBytes() > 2L * 1024 * 1024
        }
        assertTrue(activeIsMobile())
        Log.i(TAG, "consent: running over mobile data, ${partialBytes() / 1024} KB")
        manager.cancelDownload()
    }

    companion object {
        const val TAG = "NetworkSwitchCheck"
    }
}
