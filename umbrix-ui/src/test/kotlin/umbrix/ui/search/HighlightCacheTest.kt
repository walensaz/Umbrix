package umbrix.ui.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HighlightCacheTest {

    @Test
    fun testHighlightCacheReturnsSameInstanceOnHit() {
        HighlightCache.clear()
        val text = "2026-09-22 12:00:00 [ERROR] Database connection lost"
        val needles = listOf("ERROR", "Database")

        val first = buildHighlightedLine(text, needles, isMatch = true, isActiveMatch = false)
        val second = buildHighlightedLine(text, needles, isMatch = true, isActiveMatch = false)

        assertSame(first, second, "Memoized call must return identical cached AnnotatedString instance")
        assertEquals(1, HighlightCache.size())
    }

    @Test
    fun testHighlightCacheDifferentiatesActiveMatch() {
        HighlightCache.clear()
        val text = "2026-09-22 12:00:00 [ERROR] Database connection lost"
        val needles = listOf("ERROR")

        val inactive = buildHighlightedLine(text, needles, isMatch = true, isActiveMatch = false)
        val active = buildHighlightedLine(text, needles, isMatch = true, isActiveMatch = true)

        assertNotSame(inactive, active, "Active match must have distinct styling from inactive match")
        assertEquals(2, HighlightCache.size())
    }

    @Test
    fun testFindNeedleIntervalsFindsMultipleInstancesOnSameLine() {
        val text = "ERROR first error and second ERROR in line"
        val needles = listOf("ERROR")
        val intervals = findNeedleIntervals(text, needles)

        // Case-insensitive search finds "ERROR", "error", and "ERROR"
        assertEquals(3, intervals.size)
        assertEquals(Pair(0, 5), intervals[0])
        assertEquals(Pair(12, 17), intervals[1])
        assertEquals(Pair(29, 34), intervals[2])
    }

    @Test
    fun testFindNeedleIntervalsCaseSensitive() {
        val text = "ERROR first error and second ERROR in line"
        val needles = listOf("ERROR")
        val intervals = findNeedleIntervals(text, needles, isMatchCase = true)

        // Case-sensitive search only finds "ERROR" uppercase
        assertEquals(2, intervals.size)
        assertEquals(Pair(0, 5), intervals[0])
        assertEquals(Pair(29, 34), intervals[1])
    }

    @Test
    fun testFindNeedleIntervalsWholeWord() {
        val text = "err ERROR error ERRORS error_code ERROR"
        val needles = listOf("ERROR")
        val intervals = findNeedleIntervals(text, needles, isMatchCase = false, isWholeWord = true)

        // Whole word matches only standalone "ERROR" or "error" bounded by word boundaries
        // "err", "ERRORS", and "error_code" are excluded
        assertEquals(3, intervals.size)
        assertEquals(Pair(4, 9), intervals[0])
        assertEquals(Pair(10, 15), intervals[1])
        assertEquals(Pair(34, 39), intervals[2])
    }

    @Test
    fun testFindNeedleIntervalsRegex() {
        val text = "2026-09-24 [ERROR] connection refused, code 404"
        val needles = listOf("code\\s+\\d+")
        val intervals = findNeedleIntervals(text, needles, isMatchCase = false, isWholeWord = false, isRegex = true)

        assertEquals(1, intervals.size)
        assertEquals(Pair(39, 47), intervals[0])
        assertEquals("code 404", text.substring(intervals[0].first, intervals[0].second))
    }

    @Test
    fun testHighlightCacheDifferentiatesModes() {
        HighlightCache.clear()
        val text = "ERROR error"
        val needles = listOf("ERROR")

        val caseInsensitive = buildHighlightedLine(text, needles, isMatch = true, isMatchCase = false)
        val caseSensitive = buildHighlightedLine(text, needles, isMatch = true, isMatchCase = true)
        val wholeWord = buildHighlightedLine(text, needles, isMatch = true, isWholeWord = true)
        val regex = buildHighlightedLine(text, needles, isMatch = true, isRegex = true)

        assertNotSame(caseInsensitive, caseSensitive)
        assertNotSame(caseInsensitive, wholeWord)
        assertNotSame(caseInsensitive, regex)
    }

    @Test
    fun testHighlightCacheDifferentiatesActiveMatchColumn() {
        HighlightCache.clear()
        val text = "ERROR first error and second ERROR in line"
        val needles = listOf("ERROR")

        val matchCol0 = buildHighlightedLine(text, needles, isMatch = true, isActiveMatch = true, activeMatchColumn = 0)
        val matchCol29 = buildHighlightedLine(text, needles, isMatch = true, isActiveMatch = true, activeMatchColumn = 29)

        assertNotSame(matchCol0, matchCol29, "Different active match columns on the same line must produce distinct cached instances")
        assertEquals(2, HighlightCache.size())
    }

    @Test
    fun testHighlightCacheBoundedEviction() {
        HighlightCache.clear()
        val needles = listOf("TEST")

        for (i in 0..700) {
            buildHighlightedLine("Line content $i", needles, isMatch = true, isActiveMatch = false)
        }

        assertTrue(HighlightCache.size() <= 512, "HighlightCache must be bounded to 512 entries")
    }

    @Test
    fun testHighlightCacheMicrobenchmark() {
        HighlightCache.clear()
        val needles = listOf("ERROR", "Worker", "connection")
        val lines = (0 until 60).map { "2026-09-22 12:00:$it [ERROR] Worker thread error in connection pool $it" }

        // Warm up cache
        lines.forEach { buildHighlightedLine(it, needles, isMatch = true, isActiveMatch = false) }

        // Simulate 5,000 frames of scrolling/rendering 60 rows
        val iterations = 5_000
        val t0 = System.nanoTime()
        var totalChars = 0
        for (frame in 0 until iterations) {
            for (line in lines) {
                val annotated = buildHighlightedLine(line, needles, isMatch = true, isActiveMatch = false)
                totalChars += annotated.text.length
            }
        }
        val elapsedNanos = System.nanoTime() - t0
        val totalCalls = iterations * lines.size
        val elapsedMs = elapsedNanos / 1e6
        val opsPerSec = (totalCalls * 1e9) / elapsedNanos

        println("=== HighlightCache Scrolling Benchmark ($totalCalls calls across $iterations frames) ===")
        println("Elapsed time: %.2f ms (%.0f calls/sec)".format(elapsedMs, opsPerSec))
        println("Time per viewport frame (60 rows): %.3f µs".format((elapsedNanos / 1000.0) / iterations))

        val minOpsPerSec = if (System.getenv("CI") != null) 200_000.0 else 1_000_000.0
        assertTrue(opsPerSec > minOpsPerSec, "Memoized highlighting must exceed $minOpsPerSec calls/sec (got $opsPerSec)")
    }
}
