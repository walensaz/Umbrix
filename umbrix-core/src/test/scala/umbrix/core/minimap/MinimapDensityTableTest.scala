package umbrix.core.minimap

import munit.FunSuite
import umbrix.core.profile.LineSeverity

class MinimapDensityTableTest extends FunSuite {

  test("MinimapDensityTable correctly distributes lines into 1,000 bins") {
    val table = new MinimapDensityTable(1000)
    val totalLines = 100_000L

    assertEquals(table.binForLine(0L, totalLines), 0)
    assertEquals(table.binForLine(50_000L, totalLines), 500)
    assertEquals(table.binForLine(99_999L, totalLines), 999)
    assertEquals(table.binForLine(100_000L, totalLines), 999)

    // Reverse lookup
    assertEquals(table.lineForBin(0, totalLines), 0L)
    assertEquals(table.lineForBin(500, totalLines), 50_000L)
    assertEquals(table.lineForBin(999, totalLines), 99_900L)
  }

  test("MinimapDensityTable accumulates multi-channel counts and computes maximums") {
    val table = new MinimapDensityTable(1000)

    table.increment(10, LineSeverity.Error)
    table.increment(10, LineSeverity.Error)
    table.increment(10, LineSeverity.Warn)
    table.increment(10, LineSeverity.Info)

    table.increment(20, LineSeverity.Warn)
    table.increment(20, LineSeverity.Warn)
    table.increment(20, LineSeverity.Warn)

    assertEquals(table.errorCount(10), 2)
    assertEquals(table.warnCount(10), 1)
    assertEquals(table.infoCount(10), 1)
    assertEquals(table.totalCount(10), 4)

    assertEquals(table.errorCount(20), 0)
    assertEquals(table.warnCount(20), 3)
    assertEquals(table.totalCount(20), 3)

    assertEquals(table.maxError, 2)
    assertEquals(table.maxWarn, 3)
    assertEquals(table.maxTotal, 4)
  }
}
