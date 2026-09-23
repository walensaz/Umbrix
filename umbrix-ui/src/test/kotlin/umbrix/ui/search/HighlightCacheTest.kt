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

        assertTrue(opsPerSec > 1_000_000.0, "Memoized highlighting must exceed 1,000,000 calls/sec")
    }
}
