package umbrix.core.session

import java.text.NumberFormat

/**
 * Pure formatting and progress-calculation helpers for [LogSession] status reporting.
 * Extracted to keep [LogSession] focused on off-heap resource lifecycle and query orchestration.
 */
object SessionStatusFormatter {

  private final val GigabytesDivisor: Double = 1024.0 * 1024.0 * 1024.0
  private final val MegabytesDivisor: Double = 1024.0 * 1024.0
  private final val KilobytesDivisor: Double = 1024.0

  /** Converts a `[0.0, 1.0]` progress fraction into `[0.0, 99.9]` for active background stages. */
  inline def clampPercent(fraction: Double): Double = {
    math.min(99.9, math.max(0.0, fraction * 100.0))
  }

  /** Computes Tier 2 metadata indexing progress percentage (`0.0` to `100.0`). */
  def tier2ProgressPercent(
    isTier2Complete: Boolean,
    isTemporalIndexComplete: Boolean,
    isClassifierComplete: Boolean,
    temporalProgress: Double,
    classifierProgress: Double
  ): Double = {
    if (isTier2Complete) {
      100.0
    } else {
      val resolvedTemporal = if (isTemporalIndexComplete) 1.0 else temporalProgress
      val resolvedClassifier = if (isClassifierComplete) 1.0 else classifierProgress
      val combined = (resolvedTemporal + resolvedClassifier) / 2.0
      clampPercent(combined)
    }
  }

  /**
   * Computes combined background indexing progress percentage (`0.0` to `100.0`).
   * Physical indexing represents the first 70% of work; Tier 2 metadata represents the remaining 30%.
   */
  def indexingProgressPercent(
    isIndexingComplete: Boolean,
    isFusedIndexing: Boolean,
    isPhysicalIndexComplete: Boolean,
    physicalPercent: Double,
    tier2Percent: Double
  ): Double = {
    if (isIndexingComplete) {
      100.0
    } else if (isFusedIndexing) {
      physicalPercent
    } else if (!isPhysicalIndexComplete) {
      math.min(69.9, physicalPercent * 0.70)
    } else {
      math.min(99.9, 70.0 + (tier2Percent * 0.30))
    }
  }

  /** Formats a byte count into a human-readable `B`, `KB`, `MB`, or `GB` string. */
  def formatByteSize(bytes: Long): String = {
    if (bytes >= 1024L * 1024L * 1024L) {
      val gigabytes = bytes.toDouble / GigabytesDivisor
      f"$gigabytes%.2f GB"
    } else if (bytes >= 1024L * 1024L) {
      val megabytes = bytes.toDouble / MegabytesDivisor
      f"$megabytes%.2f MB"
    } else if (bytes >= 1024L) {
      val kilobytes = bytes.toDouble / KilobytesDivisor
      f"$kilobytes%.1f KB"
    } else {
      s"${bytes} B"
    }
  }

  /** Formats an integer value with locale-appropriate thousands separators. */
  def formatNumberWithCommas(value: Long): String = {
    NumberFormat.getIntegerInstance.format(value)
  }

  /**
   * Builds a human-readable diagnostic status string describing current indexing activity and metrics.
   */
  def indexingStatusText(
    fileName: String,
    fileSizeBytes: Long,
    bytesScanned: Long,
    totalLines: Long,
    totalEvents: Long,
    isIndexingComplete: Boolean,
    isFusedIndexing: Boolean,
    isPhysicalIndexComplete: Boolean,
    physicalPercent: Double,
    tier2Percent: Double
  ): String = {
    if (isIndexingComplete) {
      s"Ready — ${fileName} (${formatByteSize(fileSizeBytes)}, ${formatNumberWithCommas(totalLines)} lines, ${formatNumberWithCommas(totalEvents)} events)"
    } else if (isFusedIndexing) {
      val percentInt = physicalPercent.toInt
      val scannedText = formatByteSize(bytesScanned)
      val totalText = formatByteSize(fileSizeBytes)
      val linesText = formatNumberWithCommas(totalLines)
      s"Indexing & Analyzing ${fileName}: ${percentInt}% (${scannedText} / ${totalText} — ${linesText} lines)"
    } else if (!isPhysicalIndexComplete) {
      val percentInt = physicalPercent.toInt
      val scannedText = formatByteSize(bytesScanned)
      val totalText = formatByteSize(fileSizeBytes)
      val linesText = formatNumberWithCommas(totalLines)
      s"Indexing ${fileName} (SIMD): ${percentInt}% (${scannedText} / ${totalText} — ${linesText} lines)"
    } else {
      val percentInt = tier2Percent.toInt
      s"Analyzing ${fileName}: ${percentInt}% (RoaringBitmap event boundaries & checkpoints)"
    }
  }
}
