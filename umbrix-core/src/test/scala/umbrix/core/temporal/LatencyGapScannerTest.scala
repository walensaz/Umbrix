package umbrix.core.temporal

import it.unimi.dsi.fastutil.longs.LongArrayList
import munit.FunSuite
import umbrix.core.types.LineIndex
import umbrix.index.TemporalCheckpointTable

class LatencyGapScannerTest extends FunSuite {

  test("empty or single-checkpoint table yields empty LatencyGapResult") {
    val emptyTable = TemporalCheckpointTable.empty
    val resultEmpty = LatencyGapScanner.scan(emptyTable)
    assertEquals(resultEmpty.gapCount, 0)
    assertEquals(resultEmpty.isEmpty, true)
    assertEquals(resultEmpty.hasGapAt(LineIndex(0L)), false)
    assertEquals(resultEmpty.gapDurationAt(LineIndex(0L)), 0L)

    val singleLine = new LongArrayList()
    val singleMicros = new LongArrayList()
    singleLine.add(0L)
    singleMicros.add(1000000L)
    val singleTable = new TemporalCheckpointTable(singleLine, singleMicros)
    val resultSingle = LatencyGapScanner.scan(singleTable)
    assertEquals(resultSingle.gapCount, 0)
    assertEquals(resultSingle.isEmpty, true)
  }

  test("accurately detects gaps exceeding threshold and ignores normal intervals") {
    val lines = new LongArrayList()
    val micros = new LongArrayList()

    // 0: 10:00:00 (100,000,000 us)
    // 8192: 10:00:00.100 (100,100,000 us) -> delta 100ms (normal)
    // 16384: 10:00:02.500 (102,500,000 us) -> delta 2,400ms (gap > 1,000ms)
    // 24576: 10:00:02.700 (102,700,000 us) -> delta 200ms (normal)
    // 32768: 10:00:09.000 (109,000,000 us) -> delta 6,300ms (severe gap > 5,000ms)

    lines.add(0L)
    micros.add(100000000L)

    lines.add(8192L)
    micros.add(100100000L)

    lines.add(16384L)
    micros.add(102500000L)

    lines.add(24576L)
    micros.add(102700000L)

    lines.add(32768L)
    micros.add(109000000L)

    val table = new TemporalCheckpointTable(lines, micros)

    // With 1,000ms (1,000,000 us) threshold:
    val result = LatencyGapScanner.scan(table, thresholdMicros = 1000000L)
    assertEquals(result.gapCount, 2)
    assertEquals(result.isEmpty, false)

    assertEquals(result.hasGapAt(LineIndex(0L)), false)
    assertEquals(result.hasGapAt(LineIndex(8192L)), true)
    assertEquals(result.gapDurationAt(LineIndex(8192L)), 2400000L)

    assertEquals(result.hasGapAt(LineIndex(16384L)), false)
    assertEquals(result.hasGapAt(LineIndex(24576L)), true)
    assertEquals(result.gapDurationAt(LineIndex(24576L)), 6300000L)

    // With 5,000ms (5,000,000 us) severe threshold:
    val severeResult = LatencyGapScanner.scan(table, thresholdMicros = 5000000L)
    assertEquals(severeResult.gapCount, 1)
    assertEquals(severeResult.hasGapAt(LineIndex(8192L)), false)
    assertEquals(severeResult.hasGapAt(LineIndex(24576L)), true)
    assertEquals(severeResult.gapDurationAt(LineIndex(24576L)), 6300000L)
  }
}
