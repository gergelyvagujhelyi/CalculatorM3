package com.vagujhelyigergely.calculatorm3

import androidx.annotation.StringRes
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.MathSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.RecognitionResult
import com.vagujhelyigergely.calculatorm3.auth.HuggingFaceAuthManager
import com.vagujhelyigergely.calculatorm3.camera.DownloadingContent
import com.vagujhelyigergely.calculatorm3.camera.ErrorContent
import com.vagujhelyigergely.calculatorm3.camera.MobileDataWarningContent
import com.vagujhelyigergely.calculatorm3.camera.ModelCard
import com.vagujhelyigergely.calculatorm3.camera.ModelPreparingContent
import com.vagujhelyigergely.calculatorm3.camera.ModelUnusableContent
import com.vagujhelyigergely.calculatorm3.camera.ScanQualitySwitch
import com.vagujhelyigergely.calculatorm3.ai.ScanQuality
import com.vagujhelyigergely.calculatorm3.camera.ScanViewModel
import com.vagujhelyigergely.calculatorm3.camera.SuccessContent
import com.vagujhelyigergely.calculatorm3.ui.theme.CalculatorM3Theme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * The AI Math Solver screens, rendered state by state with fake data: no model, network or
 * camera is involved, so the results don't depend on what's installed on the device.
 *
 * Tests that would write the app's real settings run only when the instrumentation argument
 * `allowDataWrites=true` is passed (CI does, on a throwaway emulator); on a personal phone
 * they're skipped.
 */
class AiScreensUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(@StringRes id: Int, vararg args: Any) = context.getString(id, *args)

    private fun allowDataWrites() =
        InstrumentationRegistry.getArguments().getString("allowDataWrites") == "true"

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent { CalculatorM3Theme { content() } }
    }

    private fun button(text: String) = composeTestRule.onNode(hasText(text) and hasClickAction())

    // ── Mobile-data warning ─────────────────────────────────────────────────

    @Test
    fun mobileDataWarningNamesTheSizeAndRoutesBothChoices() {
        val model = AiModel.GEMMA4_E2B
        var continued = 0
        var cancelled = 0
        show {
            MobileDataWarningContent(
                model = model,
                onContinue = { continued++ },
                onCancel = { cancelled++ },
                onDismiss = {}
            )
        }
        composeTestRule.onNodeWithText(str(R.string.mobile_data_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.mobile_data_description, model.totalSizeDisplay))
            .assertIsDisplayed()

        button(str(R.string.download_anyway)).performClick()
        assertEquals(1, continued)
        button(str(R.string.cancel)).performClick()
        assertEquals(1, cancelled)
    }

    // ── Model cards ─────────────────────────────────────────────────────────

    private fun showCard(
        model: AiModel,
        isDownloaded: Boolean,
        tooLarge: Boolean,
        deviceRamGb: Int,
        onSelect: (AiModel) -> Unit = {},
        onDownload: (AiModel) -> Unit = {}
    ) = show {
        ModelCard(
            model = model,
            isDownloaded = isDownloaded,
            isSelected = false,
            tooLarge = tooLarge,
            deviceRamGb = deviceRamGb,
            onSelectModel = onSelect,
            onDownloadModel = onDownload
        )
    }

    @Test
    fun modelTooLargeForTheDeviceSaysWhyAndDoesNothingWhenTapped() {
        val model = AiModel.GEMMA4_E2B
        val calls = mutableListOf<String>()
        showCard(model, isDownloaded = false, tooLarge = true, deviceRamGb = model.minRamGb - 2,
            onSelect = { calls += "select" }, onDownload = { calls += "download" })

        composeTestRule.onNodeWithText(str(R.string.model_too_large, model.minRamGb, model.minRamGb - 2))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(model.displayName).performClick()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun modelNotYetDownloadedStartsADownloadWhenTapped() {
        val model = AiModel.GEMMA4_E2B
        var downloaded: AiModel? = null
        showCard(model, isDownloaded = false, tooLarge = false, deviceRamGb = 12,
            onDownload = { downloaded = it })
        composeTestRule.onNodeWithText("${model.totalSizeDisplay} · ${model.minRamGb} GB+ RAM").assertIsDisplayed()
        composeTestRule.onNodeWithText(model.displayName).performClick()
        assertEquals(model, downloaded)
    }

    @Test
    fun downloadedModelIsSelectedWhenTapped() {
        val model = AiModel.GEMMA4_E2B
        var selected: AiModel? = null
        showCard(model, isDownloaded = true, tooLarge = false, deviceRamGb = 12,
            onSelect = { selected = it })
        composeTestRule.onNodeWithContentDescription(str(R.string.downloaded)).assertIsDisplayed()
        composeTestRule.onNodeWithText(model.displayName).performClick()
        assertEquals(model, selected)
    }

    // ── Result and error screens ────────────────────────────────────────────

    @Test
    fun answerScreenOffersTheAnswerAndRetry() {
        var used = 0
        var retried = 0
        show {
            SuccessContent(
                answer = "42", rawResponse = "6 × 7 = 42\n42", elapsedMs = 1500, tokenCount = 12,
                backend = "GPU", ttftMs = 800, decodeTokensPerSec = 20.0,
                onUse = { used++ }, onRetry = { retried++ }, onDismiss = {}
            )
        }
        composeTestRule.onNodeWithText(str(R.string.answer_found)).assertIsDisplayed()
        button(str(R.string.use_answer, "42")).performClick()
        assertEquals(1, used)
        button(str(R.string.retry)).performClick()
        assertEquals(1, retried)
    }

    @Test
    fun errorScreenShowsTheModelOutputOpenAndRetries() {
        var retried = 0
        val raw = "I see 1 / 0\nDivision by zero is undefined"
        show {
            ErrorContent(
                message = str(R.string.error_total_timeout),
                rawResponse = raw,
                onRetry = { retried++ },
                onDismiss = {}
            )
        }
        // A real failure keeps its title and reason, and the output starts open.
        composeTestRule.onNodeWithText(str(R.string.recognition_error)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.error_total_timeout)).assertIsDisplayed()
        composeTestRule.onNodeWithTag("model-output").assertExists()

        // It can still be collapsed.
        button(str(R.string.raw_model_output)).performClick()
        composeTestRule.onNodeWithTag("model-output").assertDoesNotExist()

        button(str(R.string.retry)).performClick()
        assertEquals(1, retried)
    }

    @Test
    fun answerWithoutASingleNumberPointsToTheOutputInsteadOfFailing() {
        show {
            ErrorContent(
                message = str(R.string.error_no_numerical_answer),
                rawResponse = "1/2 + 3/4 = 5/4\n\n5/4",
                onRetry = {},
                onDismiss = {},
                checkOutput = true
            )
        }
        composeTestRule.onNodeWithText(str(R.string.check_output_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.check_output_hint)).assertExists()
        composeTestRule.onNodeWithText(str(R.string.recognition_error)).assertDoesNotExist()
        composeTestRule.onNodeWithTag("model-output").assertExists()
    }

    @Test
    fun errorWithoutModelOutputHasNoOutputSection() {
        show {
            ErrorContent(message = str(R.string.error_image_process), rawResponse = null, onRetry = {}, onDismiss = {})
        }
        composeTestRule.onNodeWithText(str(R.string.recognition_error)).assertIsDisplayed()
        button(str(R.string.raw_model_output)).assertDoesNotExist()
    }

    // ── Download progress ───────────────────────────────────────────────────

    @Test
    fun downloadProgressShowsFileCountPercentageAndCancels() {
        var cancelled = 0
        show {
            DownloadingContent(
                currentFile = "Gemma 4 E2B (2.6 GB)",
                downloadedBytes = 512L * 1024 * 1024,
                totalBytes = 1024L * 1024 * 1024,
                fileIndex = 0,
                fileCount = 2,
                onCancel = { cancelled++ },
                onDismiss = {}
            )
        }
        composeTestRule.onNodeWithText(str(R.string.download_file_progress, 1, 2)).assertIsDisplayed()
        composeTestRule.onNodeWithText("(50%)", substring = true).assertIsDisplayed()
        button(str(R.string.cancel)).performClick()
        assertEquals(1, cancelled)
    }

    // ── One-time model preparation (new engine version / fresh download) ──

    // ── Speed/quality switch ────────────────────────────────────────────────

    @Test
    fun scanQualitySwitchShowsThreeLevelsAndReportsTheChoice() {
        val chosen = mutableListOf<ScanQuality>()
        show { ScanQualitySwitch(ScanQuality.ACCURATE) { chosen += it } }
        composeTestRule.onNodeWithText(str(R.string.scan_quality_accurate)).assertIsSelected()
        composeTestRule.onNodeWithText(str(R.string.scan_quality_fast)).assertIsNotSelected()
        composeTestRule.onNodeWithText(str(R.string.scan_quality_hint)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.scan_quality_fast)).performClick()
        composeTestRule.onNodeWithText(str(R.string.scan_quality_balanced)).performClick()
        assertEquals(listOf(ScanQuality.FAST, ScanQuality.BALANCED), chosen)
    }

    // ── Model missing or unloadable ─────────────────────────────────────────

    @Test
    fun missingModelOffersOnlyToDownloadItAgain() {
        val model = AiModel.GEMMA4_E2B
        var redownloads = 0
        show {
            ModelUnusableContent(
                model = model, missing = true, detail = "",
                onRedownload = { redownloads++ }, onRetry = {}, onDismiss = {}
            )
        }
        composeTestRule.onNodeWithText(str(R.string.model_missing_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            str(R.string.model_missing_description, model.displayName, model.totalSizeDisplay)
        ).assertIsDisplayed()
        button(str(R.string.retry)).assertDoesNotExist()
        button(str(R.string.download_again)).performClick()
        assertEquals(1, redownloads)
    }

    @Test
    fun unloadableModelShowsTheErrorAndOffersRetryOrDownloadAgain() {
        val model = AiModel.GEMMA4_E2B
        var redownloads = 0
        var retries = 0
        show {
            ModelUnusableContent(
                model = model, missing = false, detail = "Unable to open zip archive.",
                onRedownload = { redownloads++ }, onRetry = { retries++ }, onDismiss = {}
            )
        }
        composeTestRule.onNodeWithText(str(R.string.model_unusable_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Unable to open zip archive.").assertIsDisplayed()
        button(str(R.string.retry)).performClick()
        assertEquals(1, retries)
        button(str(R.string.download_again)).performClick()
        assertEquals(1, redownloads)
    }

    @Test
    fun engineUpdateShowsTheNewModelFormatMessage() {
        show { ModelPreparingContent(isUpdate = true, startTimeMs = System.currentTimeMillis(), onDismiss = {}) }
        composeTestRule.onNodeWithText(str(R.string.model_format_update_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.model_preparing_hint)).assertIsDisplayed()
    }

    @Test
    fun freshDownloadShowsThePreparingMessage() {
        show { ModelPreparingContent(isUpdate = false, startTimeMs = System.currentTimeMillis(), onDismiss = {}) }
        composeTestRule.onNodeWithText(str(R.string.model_preparing_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.model_format_update_title)).assertDoesNotExist()
    }

    // ── The 7× AC unlock (writes the real AI-unlock setting) ────────────────

    /** A solver that never runs: the unlock test only needs the camera button to appear. */
    private class FakeSolver : MathSolver {
        override val isModelLoaded = false
        override val activeBackend = "—"
        override suspend fun loadModel(modelPath: String) = Unit
        override suspend fun solveFromImageStreaming(
            imagePath: String,
            onToken: suspend (partialRaw: String) -> Unit
        ): Result<RecognitionResult> = Result.failure(UnsupportedOperationException())
        override suspend fun release() = Unit
    }

    @Test
    fun sevenRapidAllClearTapsRevealTheCameraButton() {
        assumeTrue("Writes the app's real AI-unlock setting; runs only with allowDataWrites=true (CI emulator)",
            allowDataWrites())
        val scanViewModel = ScanViewModel(FakeSolver(), ModelManager(context), HuggingFaceAuthManager(context), context)
        show { CalculatorScreen(viewModel = CalculatorViewModel(), scanViewModel = scanViewModel) }
        val camera = hasContentDescription(str(R.string.scan_expression))
        assumeTrue("AI features already unlocked on this device",
            composeTestRule.onAllNodes(camera).fetchSemanticsNodes().isEmpty())

        repeat(7) { button("AC").performClick() }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(camera).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNode(camera).assertIsDisplayed()
    }
}
