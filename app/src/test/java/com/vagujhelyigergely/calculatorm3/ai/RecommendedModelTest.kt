package com.vagujhelyigergely.calculatorm3.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Recommended" in AI settings: the biggest Gemma 4 model the device's RAM can run. */
class RecommendedModelTest {

    @Test
    fun `8 GB or more gets the bigger Gemma 4 model`() {
        assertEquals(AiModel.GEMMA4_E4B, AiModel.recommendedFor(8))
        assertEquals(AiModel.GEMMA4_E4B, AiModel.recommendedFor(12))
    }

    @Test
    fun `6 GB gets the smaller Gemma 4 model`() {
        assertEquals(AiModel.GEMMA4_E2B, AiModel.recommendedFor(6))
    }

    @Test
    fun `a device too small for any model still gets the smallest one`() {
        assertEquals(AiModel.GEMMA4_E2B, AiModel.recommendedFor(4))
    }

    @Test
    fun `the previous-generation login models are never recommended`() {
        for (ram in 0..24) {
            assertEquals(false, AiModel.recommendedFor(ram).requiresAuth)
        }
    }
}
