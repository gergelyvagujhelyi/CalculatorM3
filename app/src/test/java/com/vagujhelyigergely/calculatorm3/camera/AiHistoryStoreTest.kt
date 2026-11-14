package com.vagujhelyigergely.calculatorm3.camera

import com.vagujhelyigergely.calculatorm3.ai.ScanQuality
import com.vagujhelyigergely.calculatorm3.ai.SolvedSteps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AiHistoryStoreTest {

    private val dir: File = Files.createTempDirectory("ai_history").toFile()
    private val store = AiHistoryStore(dir, maxEntries = 3)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun typed(time: Long, text: String = "6 × 7") = AiHistoryEntry(
        id = "", timestamp = time, mode = AiEntry.TYPE, typedText = text,
        output = "Problem: $text\nAnswer: 42", outcome = AiHistoryOutcome.SOLVED, answer = "42",
        steps = SolvedSteps(text, "1. 6 × 7 = 42", "42", "42"), model = "Gemma 4 E2B", elapsedMs = 9_000
    )

    @Test
    fun `every kind of entry survives a save and a load`() {
        val drawn = AiHistoryEntry(
            id = "", timestamp = 2, mode = AiEntry.WRITE,
            strokes = listOf(InkStroke(listOf(InkPoint(0.1f, 0.2f), InkPoint(0.3f, 0.4f))), InkStroke(listOf(InkPoint(0.5f, 0.5f)))),
            output = "Problem: 7 + 5\nAnswer: 12", outcome = AiHistoryOutcome.SOLVED, answer = "12",
            steps = SolvedSteps("7 + 5", "", "12", "12"), model = "Gemma 4 E2B", speed = ScanQuality.FAST, elapsedMs = 15_000
        )
        val photo = AiHistoryEntry(
            id = "", timestamp = 3, mode = AiEntry.CAMERA, output = "5/4", outcome = AiHistoryOutcome.CHECK,
            message = "Could not extract a numerical answer", model = "Gemma 3n E2B", elapsedMs = 20_000
        )
        val saved = listOf(store.add(typed(1)), store.add(drawn), store.add(photo))
        val loaded = store.load()
        assertEquals(saved.reversed(), loaded)
        assertEquals(drawn.strokes, loaded[1].strokes)
        assertEquals(ScanQuality.FAST, loaded[1].speed)
        assertNull(loaded[0].speed)
    }

    @Test
    fun `a picture is copied in and deleted with its entry`() {
        val source = File(dir.parentFile, "src_${System.nanoTime()}.jpg").apply { writeText("jpeg") }
        try {
            val saved = store.add(
                AiHistoryEntry(id = "", timestamp = 5, mode = AiEntry.GALLERY, output = "42",
                    outcome = AiHistoryOutcome.SOLVED, answer = "42", model = "m", elapsedMs = 1),
                image = source
            )
            val picture = store.imageOf(saved)
            assertNotNull(picture)
            assertEquals("jpeg", picture!!.readText())
            assertTrue("the original stays where it was (the scan deletes it)", source.exists())
            store.delete(saved.id)
            assertFalse(picture.exists())
            assertTrue(store.load().isEmpty())
        } finally {
            source.delete()
        }
    }

    @Test
    fun `only the newest entries are kept`() {
        (1L..5L).forEach { store.add(typed(it, "problem $it")) }
        assertEquals(listOf("problem 5", "problem 4", "problem 3"), store.load().map { it.typedText })
    }

    @Test
    fun `clearing removes everything and a damaged file is skipped`() {
        store.add(typed(1))
        File(dir, "broken.json").writeText("{ not json")
        assertEquals(1, store.load().size)
        store.clear()
        assertTrue(store.load().isEmpty())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun `an entry can be found by its id`() {
        val saved = store.add(typed(7))
        assertEquals(saved, store.find(saved.id))
        assertNull(store.find("missing"))
    }

    @Test
    fun `follow-ups are saved with their entry and an update keeps the picture`() {
        val source = File(dir.parentFile, "src_${System.nanoTime()}.jpg").apply { writeText("jpeg") }
        try {
            val saved = store.add(typed(9).copy(mode = AiEntry.CAMERA, typedText = null), image = source)
            val asked = saved.copy(followUps = listOf(FollowUp("Why 42?", "Because 6 × 7 = 42.")))
            store.update(asked)
            val loaded = store.find(saved.id)!!
            assertEquals(asked.followUps, loaded.followUps)
            assertEquals(saved.imageFile, loaded.imageFile)
            assertNotNull(store.imageOf(loaded))
            assertEquals(1, store.load().size)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `updating an entry that was deleted doesn't bring it back`() {
        val saved = store.add(typed(10))
        store.delete(saved.id)
        store.update(saved.copy(followUps = listOf(FollowUp("q", "a"))))
        assertTrue(store.load().isEmpty())
    }
}
