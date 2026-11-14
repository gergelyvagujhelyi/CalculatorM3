package com.vagujhelyigergely.calculatorm3

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.vagujhelyigergely.calculatorm3.ai.AiModel
import com.vagujhelyigergely.calculatorm3.ai.MathSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.ai.RecognitionResult
import com.vagujhelyigergely.calculatorm3.auth.HuggingFaceAuthManager
import com.vagujhelyigergely.calculatorm3.camera.AiEntry
import com.vagujhelyigergely.calculatorm3.camera.AiMenuContent
import com.vagujhelyigergely.calculatorm3.camera.FollowUp
import com.vagujhelyigergely.calculatorm3.camera.FollowUpSection
import com.vagujhelyigergely.calculatorm3.camera.ScanViewModel.FollowUpUi
import com.vagujhelyigergely.calculatorm3.camera.AiHistoryDetail
import com.vagujhelyigergely.calculatorm3.camera.AiHistoryEntry
import com.vagujhelyigergely.calculatorm3.camera.AiHistoryList
import com.vagujhelyigergely.calculatorm3.camera.AiHistoryOutcome
import com.vagujhelyigergely.calculatorm3.camera.InkPoint
import com.vagujhelyigergely.calculatorm3.camera.InkStroke
import com.vagujhelyigergely.calculatorm3.camera.WRITE_PAD_TAG
import com.vagujhelyigergely.calculatorm3.camera.WriteContent
import com.vagujhelyigergely.calculatorm3.camera.StepsSuccessContent
import com.vagujhelyigergely.calculatorm3.camera.TypeContent
import com.vagujhelyigergely.calculatorm3.ai.SolvedSteps
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
import org.junit.Assert.assertTrue
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
        onDownload: (AiModel) -> Unit = {},
        isRecommended: Boolean = false
    ) = show {
        ModelCard(
            model = model,
            isDownloaded = isDownloaded,
            isSelected = false,
            tooLarge = tooLarge,
            deviceRamGb = deviceRamGb,
            onSelectModel = onSelect,
            onDownloadModel = onDownload,
            isRecommended = isRecommended
        )
    }

    @Test
    fun onlyTheRecommendedModelIsLabelledSo() {
        showCard(AiModel.GEMMA4_E4B, isDownloaded = false, tooLarge = false, deviceRamGb = 8,
            isRecommended = true)
        composeTestRule.onNodeWithText(str(R.string.model_recommended)).assertIsDisplayed()
    }

    @Test
    fun otherModelsHaveNoRecommendedLabel() {
        showCard(AiModel.GEMMA4_E2B, isDownloaded = false, tooLarge = false, deviceRamGb = 8)
        composeTestRule.onNodeWithText(str(R.string.model_recommended)).assertDoesNotExist()
    }

    @Test
    fun modelTooLargeForTheDeviceSaysWhyAndDoesNothingWhenTapped() {
        val model = AiModel.GEMMA4_E2B
        val calls = mutableListOf<String>()
        showCard(model, isDownloaded = false, tooLarge = true, deviceRamGb = model.minRamGb - 2,
            onSelect = { calls += "select" }, onDownload = { calls += "download" })

        composeTestRule.onNodeWithText(str(R.string.model_too_large, model.minRamGb, model.minRamGb - 2))
            .assertIsDisplayed()
        // The size line doesn't repeat the RAM figure the red line already gives.
        composeTestRule.onNodeWithText(str(R.string.model_size_download, model.totalSizeDisplay))
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
        composeTestRule.onNodeWithText(str(R.string.model_desc_gemma4_e2b)).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            str(R.string.model_size_download_ram, model.totalSizeDisplay, model.minRamGb)
        ).assertIsDisplayed()
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
        composeTestRule.onNodeWithText(str(R.string.model_size_downloaded, model.totalSizeDisplay))
            .assertIsDisplayed()
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
                answer = "42", rawResponse = "6 × 7 = 42\n42", elapsedMs = 1500,
                onUse = { used++ }, onRetry = { retried++ }, onDismiss = {}
            )
        }
        composeTestRule.onNodeWithText(str(R.string.answer_found)).assertIsDisplayed()
        // No developer speed readout (it used to say "GPU · 20.0 tok/s decode · ttft …").
        composeTestRule.onNodeWithText("tok/s", substring = true).assertDoesNotExist()
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

    @Test
    fun scanQualitySwitchIsDisabledWithANoteForModelsWithoutIt() {
        show { ScanQualitySwitch(ScanQuality.ACCURATE, onChange = {}, enabled = false) }
        composeTestRule.onNodeWithText(str(R.string.scan_quality_fast)).assertIsNotEnabled()
        composeTestRule.onNodeWithText(str(R.string.scan_quality_unavailable)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.scan_quality_hint)).assertDoesNotExist()
    }

    // ── AI menu ─────────────────────────────────────────────────────────────

    @Test
    fun aiMenuOffersCameraGalleryAndSettings() {
        val picked = mutableListOf<AiEntry>()
        show { AiMenuContent(onSelect = { picked += it }) }
        composeTestRule.onNodeWithText(str(R.string.ai_menu_title)).assertIsDisplayed()
        button(str(R.string.ai_menu_camera)).performClick()
        button(str(R.string.ai_menu_gallery)).performClick()
        button(str(R.string.ai_menu_write)).performClick()
        button(str(R.string.ai_menu_type)).performClick()
        composeTestRule.onNodeWithContentDescription(str(R.string.ai_settings)).performClick()
        assertEquals(listOf(AiEntry.CAMERA, AiEntry.GALLERY, AiEntry.WRITE, AiEntry.TYPE, AiEntry.SETTINGS), picked)
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
    fun theAiButtonShowsByDefaultAndSevenQuickAcTapsBringItBackWhenHidden() {
        assumeTrue("Writes the app's real AI-button setting; runs only with allowDataWrites=true (CI emulator)",
            allowDataWrites())
        val manager = ModelManager(context)
        assumeTrue("This device can't run any model, so the AI button never shows", manager.canRunAnyModel)
        val scanViewModel = ScanViewModel(FakeSolver(), manager, HuggingFaceAuthManager(context), context)
        val aiButton = hasContentDescription(str(R.string.ai_menu_title))
        try {
            kotlinx.coroutines.runBlocking { context.setAiButtonHidden(true) }
            show { CalculatorScreen(viewModel = CalculatorViewModel(), scanViewModel = scanViewModel) }
            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodes(aiButton).fetchSemanticsNodes().isEmpty()
            }

            repeat(7) { button("AC").performClick() }

            composeTestRule.waitUntil(timeoutMillis = 5_000) {
                composeTestRule.onAllNodes(aiButton).fetchSemanticsNodes().isNotEmpty()
            }
            composeTestRule.onNode(aiButton).assertIsDisplayed()
        } finally {
            // Leave it shown, the default.
            kotlinx.coroutines.runBlocking { context.setAiButtonHidden(false) }
        }
    }

    @Test
    fun theShowAiButtonSwitchReportsTheChoice() {
        val changes = mutableListOf<Boolean>()
        show {
            var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
            com.vagujhelyigergely.calculatorm3.camera.AiButtonSetting(shown) { shown = it; changes += it }
        }
        composeTestRule.onNodeWithText(str(R.string.ai_settings_show_button_hint)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.ai_settings_show_button)).performClick()
        composeTestRule.onNodeWithText(str(R.string.ai_settings_show_button)).performClick()
        assertEquals(listOf(false, true), changes)
    }

    // ── Type screen and step-by-step answers ────────────────────────────────

    @Test
    fun typeScreenOffersTheCalculatorsOwnAnswerForArithmetic() {
        var used = 0
        var solved = 0
        show {
            TypeContent(text = "12 + 5", onTextChange = {}, instantAnswer = "17",
                onUseInstant = { used++ }, onSolve = { solved++ }, onDismiss = {})
        }
        composeTestRule.onNodeWithText("= 17").assertIsDisplayed()
        button(str(R.string.use_expression)).performClick()
        button(str(R.string.type_show_steps)).performClick()
        assertEquals(1, used)
        assertEquals(1, solved)
    }

    @Test
    fun typeScreenCantSolveNothingAndInsertsSymbols() {
        val changes = mutableListOf<String>()
        show {
            var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            TypeContent(text = text, onTextChange = { text = it; changes += it }, instantAnswer = null,
                onUseInstant = {}, onSolve = {}, onDismiss = {})
        }
        button(str(R.string.ai_menu_title)).assertIsNotEnabled()
        // The symbol row scrolls sideways; bring each key into view first.
        composeTestRule.onNodeWithText("√").performScrollTo().performClick()
        composeTestRule.onNodeWithText("π").performScrollTo().performClick()
        assertEquals(listOf("√", "√π"), changes)
        button(str(R.string.ai_menu_title)).assertIsEnabled()
    }

    @Test
    fun aNumericStepAnswerCanBeUsed() {
        var used = 0
        show {
            StepsSuccessContent(
                steps = SolvedSteps("What is 15% of 80?", "1. 0.15 × 80 = 12", "12", "12"),
                elapsedText = "Solved in 9s", onUse = { used++ }, onEdit = {}, onDismiss = {}
            )
        }
        composeTestRule.onNodeWithText(str(R.string.steps_not_usable)).assertDoesNotExist()
        button(str(R.string.use_answer, "12")).performClick()
        assertEquals(1, used)
    }

    @Test
    fun anEquationsAnswerExplainsWhyItCantBeUsedAndOffersEdit() {
        var edits = 0
        show {
            StepsSuccessContent(
                steps = SolvedSteps("3x + 5 = 20", "1. 3x = 15", "x = 5", ""),
                elapsedText = "Solved in 9s", onUse = {}, onEdit = { edits++ }, onDismiss = {}
            )
        }
        composeTestRule.onNodeWithText(str(R.string.steps_not_usable)).assertIsDisplayed()
        composeTestRule.onAllNodes(hasText(str(R.string.use_answer, ""), substring = true) and hasClickAction())
            .assertCountEquals(0)
        button(str(R.string.steps_edit)).performClick()
        assertEquals(1, edits)
    }

    // ── Write ───────────────────────────────────────────────────────────────

    private class PadLog {
        val strokes = mutableListOf<InkStroke>()
        var erases = 0
        var solves = 0
    }

    private fun showPad(initial: List<InkStroke> = emptyList()): PadLog {
        val log = PadLog()
        show {
            val strokes = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf(*initial.toTypedArray()) }
            WriteContent(
                strokes = strokes, canRedo = false, stylusOnly = false,
                onStroke = { strokes += it; log.strokes += it },
                onErase = { _, _ -> log.erases++ },
                onStylusSeen = {}, onUndo = {}, onRedo = {},
                onClear = { strokes.clear() }, onSolve = { log.solves++ }, onDismiss = {}
            )
        }
        return log
    }

    @Test
    fun anEmptyPadInvitesWritingAndCantBeSolved() {
        showPad()
        composeTestRule.onNodeWithText(str(R.string.write_hint)).assertIsDisplayed()
        button(str(R.string.ai_menu_title)).assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription(str(R.string.write_undo)).assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription(str(R.string.write_clear)).assertIsNotEnabled()
    }

    @Test
    fun aSwipeOnThePadIsOneStrokeAndEnablesSolve() {
        val log = showPad()
        composeTestRule.onNodeWithTag(WRITE_PAD_TAG).performTouchInput {
            swipe(start = center - androidx.compose.ui.geometry.Offset(100f, 0f),
                end = center + androidx.compose.ui.geometry.Offset(100f, 0f), durationMillis = 300)
        }
        composeTestRule.waitForIdle()
        assertEquals(1, log.strokes.size)
        val stroke = log.strokes.single().points
        assertTrue("several points recorded: ${stroke.size}", stroke.size >= 2)
        // Points are in pad units: fractions of the pad width.
        assertTrue(stroke.all { it.x in 0f..1f })
        composeTestRule.onNodeWithText(str(R.string.write_hint)).assertDoesNotExist()
        button(str(R.string.ai_menu_title)).assertIsEnabled().performClick()
        assertEquals(1, log.solves)
    }

    @Test
    fun withTheEraserOnASwipeErasesInsteadOfDrawing() {
        val log = showPad(listOf(InkStroke(listOf(InkPoint(0.2f, 0.2f), InkPoint(0.8f, 0.2f)))))
        composeTestRule.onNodeWithContentDescription(str(R.string.write_eraser)).performClick()
        composeTestRule.onNodeWithTag(WRITE_PAD_TAG).performTouchInput {
            swipe(start = centerLeft, end = centerRight, durationMillis = 200)
        }
        composeTestRule.waitForIdle()
        assertTrue("erased along the swipe", log.erases > 0)
        assertTrue("nothing drawn", log.strokes.isEmpty())
    }

    @Test
    fun aDrawnAnswerShowsWhatWasReadWithFixAsTextAndDrawAgain() {
        var fixes = 0
        var redraws = 0
        show {
            StepsSuccessContent(
                steps = SolvedSteps("7 + 5", "1. 7 + 5 = 12", "12", "12"),
                elapsedText = "Solved in 9s", onUse = {}, onEdit = { redraws++ }, onDismiss = {},
                source = AiEntry.WRITE, onFixAsText = { fixes++ }
            )
        }
        composeTestRule.onNodeWithText(str(R.string.steps_read_as)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.steps_fix_as_text)).performClick()
        button(str(R.string.write_again)).performClick()
        assertEquals(1, fixes)
        assertEquals(1, redraws)
    }

    // ── AI history ──────────────────────────────────────────────────────────

    private val typedEntry = AiHistoryEntry(
        id = "a", timestamp = 1_794_000_000_000, mode = AiEntry.TYPE, typedText = "What is 15% of 80?",
        output = "Problem: What is 15% of 80?\n1. 0.15 × 80 = 12\nAnswer: 12", outcome = AiHistoryOutcome.SOLVED,
        answer = "12", steps = SolvedSteps("What is 15% of 80?", "1. 0.15 × 80 = 12", "12", "12"),
        model = "Gemma 4 E2B", elapsedMs = 19_000
    )
    private val checkEntry = typedEntry.copy(
        id = "b", typedText = "1/2 + 3/4", output = "It is 5/4.", outcome = AiHistoryOutcome.CHECK,
        answer = "", steps = null, message = "Could not extract a numerical answer"
    )

    @Test
    fun anEmptyAiHistorySaysWhereAnswersWillAppear() {
        show { AiHistoryList(emptyList(), imageOf = { null }, onOpen = {}, onDelete = {}) }
        composeTestRule.onNodeWithText(str(R.string.ai_history_empty)).assertIsDisplayed()
    }

    @Test
    fun aiHistoryRowsShowTheProblemAndAnswerAndOpenOnTap() {
        val opened = mutableListOf<String>()
        show { AiHistoryList(listOf(typedEntry, checkEntry), imageOf = { null }, onOpen = { opened += it.id }, onDelete = {}) }
        composeTestRule.onNodeWithText("What is 15% of 80?").assertIsDisplayed()
        composeTestRule.onNodeWithText("12").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.ai_history_outcome_check)).assertIsDisplayed()
        composeTestRule.onNodeWithText("1/2 + 3/4").performClick()
        assertEquals(listOf("b"), opened)
    }

    @Test
    fun anAiHistoryEntryShowsItsFullOutputWithUseSolveAgainAndDelete() {
        val used = mutableListOf<String>()
        var again = 0
        var deleted = 0
        show {
            AiHistoryDetail(typedEntry, image = null, onBack = {}, onUse = { used += it },
                onSolveAgain = { again++ }, onDelete = { deleted++ })
        }
        composeTestRule.onNodeWithText(str(R.string.ai_history_full_output)).assertIsDisplayed()
        composeTestRule.onNodeWithText(typedEntry.output).assertExists()
        button(str(R.string.use_answer, "12")).performScrollTo().performClick()
        button(str(R.string.ai_history_solve_again)).performScrollTo().performClick()
        button(str(R.string.ai_history_delete)).performScrollTo().performClick()
        assertEquals(listOf("12"), used)
        assertEquals(1, again)
        assertEquals(1, deleted)
    }

    @Test
    fun anEntryToCheckHasNoUseButKeepsItsOutput() {
        show { AiHistoryDetail(checkEntry, image = null, onBack = {}, onUse = {}, onSolveAgain = {}, onDelete = {}) }
        composeTestRule.onNodeWithText(str(R.string.ai_history_outcome_check)).assertExists()
        composeTestRule.onNodeWithText("It is 5/4.").assertExists()
        composeTestRule.onAllNodes(hasText(str(R.string.use_answer, ""), substring = true) and hasClickAction())
            .assertCountEquals(0)
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test
    fun theHistorySheetHasNoAiTabWhileAiIsLocked() {
        show {
            HistorySheet(
                sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
                historyList = emptyList(), onDismiss = {}, onEntryClick = {}, onClearHistory = {}, aiHistory = null
            )
        }
        composeTestRule.onNodeWithText(str(R.string.history)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.history_tab_ai)).assertDoesNotExist()
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test
    fun theHistorySheetShowsTheAiTabOnceUnlocked() {
        show {
            HistorySheet(
                sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
                historyList = emptyList(), onDismiss = {}, onEntryClick = {}, onClearHistory = {},
                aiHistory = listOf(typedEntry)
            )
        }
        composeTestRule.onNodeWithText(str(R.string.history_tab_ai)).performClick()
        composeTestRule.onNodeWithText("What is 15% of 80?").assertIsDisplayed()
        composeTestRule.onNodeWithText("What is 15% of 80?").performClick()
        composeTestRule.onNodeWithText(str(R.string.ai_history_full_output)).assertIsDisplayed()
    }

    // ── Follow-up questions ─────────────────────────────────────────────────

    @Test
    fun theFirstQuestionOffersSuggestionsAndShowsTheCounter() {
        val asked = mutableListOf<String>()
        show {
            FollowUpSection(thread = emptyList(), state = FollowUpUi.Idle, left = 2,
                onAsk = { asked += it }, onStop = {}, onSolveAgain = {})
        }
        composeTestRule.onNodeWithText(str(R.string.follow_up_left, 2)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.follow_up_chip_why)).performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("Why 0.15?")
        composeTestRule.onNodeWithContentDescription(str(R.string.follow_up_send)).performClick()
        assertEquals(listOf(str(R.string.follow_up_chip_why), "Why 0.15?"), asked)
    }

    @Test
    fun aQuestionBeingAnsweredStreamsWithStopAndHidesTheField() {
        var stops = 0
        show {
            FollowUpSection(thread = emptyList(), state = FollowUpUi.Answering("Why?", "Because 15%"), left = 2,
                onAsk = {}, onStop = { stops++ }, onSolveAgain = {})
        }
        composeTestRule.onNodeWithText("Why?").assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
        button(str(R.string.stop)).performClick()
        assertEquals(1, stops)
    }

    @Test
    fun withNoQuestionsLeftItOffersToSolveAgain() {
        var again = 0
        show {
            FollowUpSection(
                thread = listOf(FollowUp("Why?", "Because."), FollowUp("Another way?", "Yes.")),
                state = FollowUpUi.Idle, left = 0, onAsk = {}, onStop = {}, onSolveAgain = { again++ }
            )
        }
        composeTestRule.onNodeWithText("Another way?").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.follow_up_none)).assertIsDisplayed()
        composeTestRule.onNode(hasSetTextAction()).assertDoesNotExist()
        button(str(R.string.ai_history_solve_again)).performClick()
        assertEquals(1, again)
    }

    @Test
    fun aSavedAnswerShowsItsQuestionsAndHowManyAreLeft() {
        var asks = 0
        val entry = typedEntry.copy(followUps = listOf(FollowUp("Why 0.15?", "15% is 15 per 100.")))
        show {
            AiHistoryDetail(entry, image = null, onBack = {}, onUse = {}, onSolveAgain = {}, onDelete = {},
                onAskAbout = { asks++ })
        }
        composeTestRule.onNodeWithText("Why 0.15?").assertExists()
        composeTestRule.onNodeWithText(str(R.string.follow_up_left, 1)).assertExists()
        button(str(R.string.ai_history_ask)).performScrollTo().performClick()
        assertEquals(1, asks)
    }
}
