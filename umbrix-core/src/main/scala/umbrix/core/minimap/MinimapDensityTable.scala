package umbrix.core.minimap

import umbrix.core.profile.LineSeverity

/**
 * 1,000-bin density mipmap histogram engineered for real-time GPU seismograph rendering.
 *
 * Consumes exactly 16 KB of memory (4 primitive integer arrays of length 1,000), guaranteeing zero GC heap bloat.
 * Aggregates multi-channel severity distribution (Error, Warn, Info, Total) across 25GB–100GB+ files.
 */
class MinimapDensityTable(val binCount: Int = MinimapDensityTable.DefaultBinCount) {

  private val totals: Array[Int] = new Array[Int](binCount)
  private val errors: Array[Int] = new Array[Int](binCount)
  private val warns: Array[Int] = new Array[Int](binCount)
  private val infos: Array[Int] = new Array[Int](binCount)

  @volatile private var cachedMaxError: Int = 0
  @volatile private var cachedMaxWarn: Int = 0
  @volatile private var cachedMaxTotal: Int = 0

  def increment(binIndex: Int, severity: LineSeverity): Unit = {
    if (binIndex >= 0 && binIndex < binCount) {
      totals(binIndex) += 1
      if (totals(binIndex) > cachedMaxTotal) {
        cachedMaxTotal = totals(binIndex)
      }

      severity match {
        case LineSeverity.Error =>
          errors(binIndex) += 1
          if (errors(binIndex) > cachedMaxError) {
            cachedMaxError = errors(binIndex)
          }
        case LineSeverity.Warn =>
          warns(binIndex) += 1
          if (warns(binIndex) > cachedMaxWarn) {
            cachedMaxWarn = warns(binIndex)
          }
        case LineSeverity.Info =>
          infos(binIndex) += 1
        case LineSeverity.Normal =>
          ()
      }
    }
  }

  /**
   * Adds pre-aggregated or scaled counts directly to a bin.
   */
  def addCounts(binIndex: Int, total: Int, error: Int, warn: Int, info: Int): Unit = {
    if (binIndex >= 0 && binIndex < binCount) {
      totals(binIndex) += total
      if (totals(binIndex) > cachedMaxTotal) {
        cachedMaxTotal = totals(binIndex)
      }
      errors(binIndex) += error
      if (errors(binIndex) > cachedMaxError) {
        cachedMaxError = errors(binIndex)
      }
      warns(binIndex) += warn
      if (warns(binIndex) > cachedMaxWarn) {
        cachedMaxWarn = warns(binIndex)
      }
      infos(binIndex) += info
    }
  }

  def totalCount(binIndex: Int): Int = {
    if (binIndex >= 0 && binIndex < binCount) totals(binIndex) else 0
  }

  def errorCount(binIndex: Int): Int = {
    if (binIndex >= 0 && binIndex < binCount) errors(binIndex) else 0
  }

  def warnCount(binIndex: Int): Int = {
    if (binIndex >= 0 && binIndex < binCount) warns(binIndex) else 0
  }

  def infoCount(binIndex: Int): Int = {
    if (binIndex >= 0 && binIndex < binCount) infos(binIndex) else 0
  }

  def maxError: Int = math.max(1, cachedMaxError)

  def maxWarn: Int = math.max(1, cachedMaxWarn)

  def maxTotal: Int = math.max(1, cachedMaxTotal)

  def binForLine(lineIndex: Long, totalLines: Long): Int = {
    if (totalLines <= 0L || lineIndex <= 0L) {
      0
    } else if (lineIndex >= totalLines) {
      binCount - 1
    } else {
      ((lineIndex.toDouble / totalLines.toDouble) * binCount.toDouble).toInt.min(binCount - 1).max(0)
    }
  }

  def binForByteOffset(byteOffset: Long, totalBytes: Long): Int = {
    if (totalBytes <= 0L || byteOffset <= 0L) {
      0
    } else if (byteOffset >= totalBytes) {
      binCount - 1
    } else {
      ((byteOffset.toDouble / totalBytes.toDouble) * binCount.toDouble).toInt.min(binCount - 1).max(0)
    }
  }

  def lineForBin(binIndex: Int, totalLines: Long): Long = {
    if (totalLines <= 0L || binIndex <= 0) {
      0L
    } else if (binIndex >= binCount) {
      math.max(0L, totalLines - 1L)
    } else {
      val ratio = binIndex.toDouble / binCount.toDouble
      (ratio * totalLines.toDouble).toLong.min(totalLines - 1L).max(0L)
    }
  }

  def binStartLine(binIndex: Int, totalLines: Long): Long = {
    lineForBin(binIndex, totalLines)
  }

  def binEndLine(binIndex: Int, totalLines: Long): Long = {
    if (totalLines <= 0L || binIndex < 0) {
      0L
    } else {
      val nextStart = if (binIndex + 1 < binCount) lineForBin(binIndex + 1, totalLines) else totalLines
      math.max(lineForBin(binIndex, totalLines), nextStart - 1L)
    }
  }

  def lineRangeForBin(binIndex: Int, totalLines: Long): (Long, Long) = {
    (binStartLine(binIndex, totalLines), binEndLine(binIndex, totalLines))
  }

  def hasAnomalies(binIndex: Int): Boolean = {
    if (binIndex >= 0 && binIndex < binCount) {
      errors(binIndex) > 0 || warns(binIndex) > 0
    } else {
      false
    }
  }
}

object MinimapDensityTable {
  val DefaultBinCount: Int = 1000

  def empty: MinimapDensityTable = new MinimapDensityTable(DefaultBinCount)
}
