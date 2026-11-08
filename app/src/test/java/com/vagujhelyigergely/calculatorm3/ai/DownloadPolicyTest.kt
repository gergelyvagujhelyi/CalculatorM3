package com.vagujhelyigergely.calculatorm3.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {

    // ── HTTP classification ──────────────────────────────────────────

    @Test
    fun `throttling and server errors are retryable`() {
        listOf(408, 425, 429, 500, 502, 503, 504).forEach {
            assertTrue("HTTP $it", DownloadPolicy.isRetryableHttp(it))
        }
    }

    @Test
    fun `permanent client errors are not retryable`() {
        listOf(400, 401, 403, 404, 410, 416, 501).forEach {
            assertFalse("HTTP $it", DownloadPolicy.isRetryableHttp(it))
        }
    }

    @Test
    fun `retryable HTTP failure is an IOException but storage failure is not`() {
        // The worker retries IOExceptions; a full disk must not be one.
        assertTrue(RetryableHttpException(503, null) is java.io.IOException)
        assertFalse((DownloadStorageException("disk full") as Exception) is java.io.IOException)
    }

    // ── Retry-After ──────────────────────────────────────────────────

    @Test
    fun `retry-after in seconds`() {
        assertEquals(120_000L, DownloadPolicy.parseRetryAfterMs("120", nowMs = 0))
    }

    @Test
    fun `retry-after is capped`() {
        assertEquals(DownloadPolicy.MAX_RETRY_AFTER_MS, DownloadPolicy.parseRetryAfterMs("86400", nowMs = 0))
    }

    @Test
    fun `retry-after as HTTP date`() {
        // Wed, 21 Oct 2015 07:28:00 GMT = 1445412480000
        val now = 1_445_412_480_000L - 30_000L
        assertEquals(30_000L, DownloadPolicy.parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", now))
    }

    @Test
    fun `retry-after in the past waits zero`() {
        assertEquals(0L, DownloadPolicy.parseRetryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT", Long.MAX_VALUE / 2))
    }

    @Test
    fun `missing or garbage retry-after`() {
        assertNull(DownloadPolicy.parseRetryAfterMs(null, 0))
        assertNull(DownloadPolicy.parseRetryAfterMs("  ", 0))
        assertNull(DownloadPolicy.parseRetryAfterMs("soon", 0))
    }

    // ── Free space ───────────────────────────────────────────────────

    @Test
    fun `free space must cover the file plus margin`() {
        val gb = 1024L * 1024 * 1024
        assertTrue(DownloadPolicy.hasRoomFor(2 * gb, 3 * gb))
        assertFalse(DownloadPolicy.hasRoomFor(2 * gb, 2 * gb))
        assertFalse(DownloadPolicy.hasRoomFor(2 * gb, 2 * gb + DownloadPolicy.FREE_SPACE_MARGIN_BYTES - 1))
        assertTrue(DownloadPolicy.hasRoomFor(2 * gb, 2 * gb + DownloadPolicy.FREE_SPACE_MARGIN_BYTES))
    }

    // ── Bounded retries ──────────────────────────────────────────────

    @Test
    fun `retries until the limit of consecutive runs without progress`() {
        val max = DownloadPolicy.MAX_ATTEMPTS_WITHOUT_PROGRESS
        assertFalse(DownloadPolicy.shouldGiveUp(consecutiveRunsWithoutProgress = 0))
        assertFalse(DownloadPolicy.shouldGiveUp(consecutiveRunsWithoutProgress = max - 1))
        assertTrue(DownloadPolicy.shouldGiveUp(consecutiveRunsWithoutProgress = max))
    }

    @Test
    fun `progressing runs reset the streak, so a late blip does not give up`() {
        // Review scenario: 9 failed runs that each made progress, then one that got no bytes.
        var streak = 0
        repeat(9) { streak = DownloadPolicy.nextNoProgressStreak(streak, madeProgress = true) }
        streak = DownloadPolicy.nextNoProgressStreak(streak, madeProgress = false)
        assertEquals(1, streak)
        assertFalse(DownloadPolicy.shouldGiveUp(streak))
    }

    @Test
    fun `only consecutive runs without progress reach the limit`() {
        var streak = 0
        repeat(DownloadPolicy.MAX_ATTEMPTS_WITHOUT_PROGRESS - 1) {
            streak = DownloadPolicy.nextNoProgressStreak(streak, madeProgress = false)
        }
        assertFalse(DownloadPolicy.shouldGiveUp(streak))
        // One run with progress in between starts the count again.
        streak = DownloadPolicy.nextNoProgressStreak(streak, madeProgress = true)
        streak = DownloadPolicy.nextNoProgressStreak(streak, madeProgress = false)
        assertFalse(DownloadPolicy.shouldGiveUp(streak))
        // A full run of consecutive empty attempts does give up.
        streak = 0
        repeat(DownloadPolicy.MAX_ATTEMPTS_WITHOUT_PROGRESS) {
            streak = DownloadPolicy.nextNoProgressStreak(streak, madeProgress = false)
        }
        assertTrue(DownloadPolicy.shouldGiveUp(streak))
    }
}
