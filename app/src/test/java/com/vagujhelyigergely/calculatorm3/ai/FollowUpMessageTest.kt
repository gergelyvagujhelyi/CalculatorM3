package com.vagujhelyigergely.calculatorm3.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The single message a follow-up question is sent as ([SolverPrompts.followUpMessage]). */
class FollowUpMessageTest {

    @Test
    fun `the first question carries the problem, answer and working`() {
        val message = SolverPrompts.followUpMessage(
            problem = "What is 15% of 80?", answer = "12", working = "1. 0.15 × 80 = 12",
            earlier = emptyList(), question = "Why 0.15?"
        )
        assertEquals(
            "Problem: What is 15% of 80?\nAnswer: 12\nWorking:\n1. 0.15 × 80 = 12\n\nQuestion: Why 0.15?",
            message
        )
    }

    @Test
    fun `the second question also carries the first one and its reply`() {
        val message = SolverPrompts.followUpMessage(
            problem = "7 + 5", answer = "12", working = "", earlier = listOf("Why?" to "Because 7 + 5 = 12."),
            question = "Another way?"
        )
        assertEquals(
            "Problem: 7 + 5\nAnswer: 12\n\nEarlier question: Why?\nYour reply: Because 7 + 5 = 12.\n\nQuestion: Another way?",
            message
        )
    }

    @Test
    fun `only the latest earlier exchange is carried, so the context stays bounded`() {
        val message = SolverPrompts.followUpMessage(
            problem = "p", answer = "a", working = "w",
            earlier = listOf("old?" to "old reply", "new?" to "new reply"), question = "q"
        )
        assertFalse(message.contains("old reply"))
        assertEquals(true, message.contains("new reply"))
    }

    @Test
    fun `missing parts are left out rather than sent empty`() {
        val message = SolverPrompts.followUpMessage("", "", "the whole photo output", emptyList(), "What does it mean?")
        assertEquals("Working:\nthe whole photo output\n\nQuestion: What does it mean?", message)
    }
}
