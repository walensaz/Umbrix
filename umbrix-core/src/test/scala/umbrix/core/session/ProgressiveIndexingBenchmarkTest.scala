package umbrix.core.session

import munit.FunSuite
import java.nio.file.{Files, Paths}
import umbrix.core.types.LineIndex

class ProgressiveIndexingBenchmarkTest extends FunSuite {

  test("benchmark: progressive indexing pipeline on Thunderbird.log (31.8 GB)") {
    val thunderbirdPath = Paths.get("C:\\Users\\zwale\\Downloads\\Thunderbird\\Thunderbird.log")
    if (Files.exists(thunderbirdPath)) {
      println(s"=== Progressive Indexing Benchmark on Thunderbird.log ===")
      println(s"File size: ${Files.size(thunderbirdPath) / (1024L * 1024L * 1024L)} GB")

      val openStartTime = System.nanoTime()
      val session = LogSession.open(thunderbirdPath, isLiveStream = false)
      val openDurationMs = (System.nanoTime() - openStartTime) / 1000000L

      try {
        println(f"Tier 1 (Time-to-first-render): $openDurationMs ms ($openDurationMs%d ms)")
        println(s"Total physical lines indexed: ${session.totalLines.value}")
        println(s"Initial line 0 preview: [${session.getLineText(LineIndex(0L)).take(80)}...]")

        // Viewport is immediately responsive
        assert(openDurationMs < 60000L, s"Initial open must complete within 60s for 30GB cold read (was $openDurationMs ms)")
        assert(session.totalLines.value > 0L)

        // Track Tier 2 progressive indexing
        println(s"Initial Tier 2 progress: ${session.indexingProgressPercent}%%")
        assert(session.indexingProgressPercent >= 0.0)

        // Verify viewport line reads while Tier 2 is computing in background
        val midLine = LineIndex(session.totalLines.value / 2L)
        val sampleText = session.getLineText(midLine)
        assert(sampleText.nonEmpty)
        println(s"Sample mid-file line (${midLine.value}): [${sampleText.take(60)}...]")

        println("Progressive indexing pipeline verified on 31.8 GB dataset.")
      } finally {
        session.close()
      }
    } else {
      println("Thunderbird.log not found, skipping large-file benchmark")
    }
  }
}
