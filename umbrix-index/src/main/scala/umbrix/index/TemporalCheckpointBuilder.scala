package umbrix.index

import it.unimi.dsi.fastutil.longs.LongArrayList
import java.lang.foreign.{MemorySegment, ValueLayout}
import java.time.LocalDate
import java.util.concurrent.{CompletableFuture, Executor, ForkJoinPool}
import java.util.function.DoubleConsumer
import scala.util.control.NonFatal
import umbrix.core.types.LineIndex

/**
 * Off-heap builder and zero-allocation timestamp parser for [TemporalCheckpointTable].
 *
 * Samples line headers every `TemporalStrideLines` (`8,192`) lines via `MemorySegment.get(JAVA_BYTE, ...)`
 * without materializing `String` instances or boxing primitive coordinates on hot paths.
 *
 * Performs document-level timestamp format auto-detection and lock-in across initial header lines
 * to prevent mid-line numeric tokens or stack-trace payloads from injecting alien multi-day dates
 * into single-day time-of-day logs (`00:00:00.000` through `24:00:00.00`).
 */
object TemporalCheckpointBuilder {

  /** Sample interval: every 8,192 lines (2^13). */
  final val TemporalStrideLines: Long = 8192L

  final val FormatUnknown: Int = 0
  final val FormatIsoOrDotted: Int = 1
  final val FormatUsOrEurDate: Int = 2
  final val FormatTwoDigitYear: Int = 3
  final val FormatApacheClf: Int = 4
  final val FormatSyslog: Int = 5
  final val FormatMonthDayTime: Int = 6
  final val FormatTimeOfDay: Int = 7
  final val FormatUnixEpoch: Int = 8

  /** Reference date 2026-01-01T00:00:00Z in epoch microseconds (1767225600 seconds * 1,000,000). */
  final val ReferenceDateEpochMicros: Long = 1767225600000000L

  private final val MicrosPerSecond: Long = 1000000L
  private final val SecondsPerDay: Long = 86400L
  private final val HeaderDetectBytes: Long = 65536L
  private final val MaxDetectLines: Int = 64
  private final val MaxLineScanBytes: Long = 128L

  /** Asynchronously builds a [TemporalCheckpointTable] on a background executor / virtual thread. */
  def buildAsync(
    indexer: PhysicalLineIndexer,
    progressConsumer: DoubleConsumer = _ => (),
    executor: Executor = ForkJoinPool.commonPool()
  ): CompletableFuture[TemporalCheckpointTable] = {
    CompletableFuture.supplyAsync(() => build(indexer, progressConsumer), executor)
  }

  /** Builds the temporal checkpoint table by inspecting line headers at regular strides. */
  def build(
    indexer: PhysicalLineIndexer,
    progressConsumer: DoubleConsumer = _ => ()
  ): TemporalCheckpointTable = {
    val totalLines: LineIndex = indexer.totalLines
    val estimatedCheckpoints: Int = (((totalLines.value / TemporalStrideLines) + 8L)).toInt
    val linesList: LongArrayList = new LongArrayList(estimatedCheckpoints)
    val microsList: LongArrayList = new LongArrayList(estimatedCheckpoints)

    val fileSize: Long = indexer.storageReader.sizeBytes
    val headerBytesToRead: Long = math.min(fileSize, HeaderDetectBytes)
    var activeFormat: Int = if (headerBytesToRead > 0L) {
      val headerSlice: MemorySegment = indexer.storageReader.readSlice(0L, headerBytesToRead)
      detectDominantFormatFromSegment(headerSlice)
    } else {
      FormatUnknown
    }

    val totalLinesVal: Double = math.max(1.0, totalLines.value.toDouble)
    var currentLineIndex: LineIndex = LineIndex.Zero

    while (currentLineIndex < totalLines && !Thread.currentThread().isInterrupted) {
      val startOffset = indexer.byteOffsetForLine(currentLineIndex)
      val remainingBytes: Long = fileSize - startOffset.value
      if (remainingBytes > 0L) {
        val sampleLength: Long = remainingBytes.min(MaxLineScanBytes)
        val lineSlice: MemorySegment = indexer.storageReader.readSlice(startOffset.value, sampleLength)
        tryParseTimestamp(lineSlice, activeFormat) match {
          case Some(micros) =>
            if (activeFormat == FormatUnknown) {
              val detected = detectLineFormat(lineSlice)
              if (detected != FormatUnknown) {
                activeFormat = detected
              }
            }
            linesList.add(currentLineIndex.value)
            microsList.add(micros)
          case None =>
            var probeLine = currentLineIndex + 1L
            var found = false
            val maxProbe = (currentLineIndex + 16L).min(totalLines)
            while (probeLine < maxProbe && !found) {
              val probeOffset = indexer.byteOffsetForLine(probeLine)
              val probeRemaining = fileSize - probeOffset.value
              if (probeRemaining > 0L) {
                val probeSlice = indexer.storageReader.readSlice(probeOffset.value, probeRemaining.min(MaxLineScanBytes))
                tryParseTimestamp(probeSlice, activeFormat) match {
                  case Some(probeMicros) =>
                    if (activeFormat == FormatUnknown) {
                      val detected = detectLineFormat(probeSlice)
                      if (detected != FormatUnknown) {
                        activeFormat = detected
                      }
                    }
                    linesList.add(probeLine.value)
                    microsList.add(probeMicros)
                    found = true
                  case None => ()
                }
              }
              probeLine = probeLine + 1L
            }
        }
      }

      currentLineIndex = currentLineIndex + TemporalStrideLines
      progressConsumer.accept(currentLineIndex.value.toDouble / totalLinesVal)
    }

    progressConsumer.accept(1.0)
    val hasDateComponent: Boolean = activeFormat != FormatTimeOfDay
    new TemporalCheckpointTable(linesList, microsList, totalLines, hasDateComponent)
  }

  /**
   * Attempts to parse timestamps (ISO-8601, Unix Epoch, Syslog, Apache CLF, Dotted/Slashed dates,
   * Compact YYYYMMDD, Google glog, or Time-of-day) directly from leading off-heap bytes without heap allocation.
   */
  def tryParseTimestamp(segment: MemorySegment): Option[Long] = {
    parseInternal(segment, FormatUnknown, null)
  }

  /**
   * Attempts to parse a timestamp restricted to `requiredFormat` (or auto-detects when `FormatUnknown`).
   */
  def tryParseTimestamp(segment: MemorySegment, requiredFormat: Int): Option[Long] = {
    parseInternal(segment, requiredFormat, null)
  }

  /** Detects the timestamp format ID present on a single off-heap line slice. */
  def detectLineFormat(segment: MemorySegment): Int = {
    val outFormat: Array[Int] = new Array[Int](1)
    parseInternal(segment, FormatUnknown, outFormat)
    outFormat(0)
  }

  /**
   * Scans up to the first 64 lines of `headerSegment` and returns the dominant timestamp format ID
   * (`FormatIsoOrDotted` .. `FormatUnixEpoch`), or `FormatUnknown` if none matched.
   */
  def detectDominantFormatFromSegment(headerSegment: MemorySegment): Int = {
    if (headerSegment == MemorySegment.NULL || headerSegment.byteSize() < 4L) {
      FormatUnknown
    } else {
      val totalBytes: Long = headerSegment.byteSize()
      val formatCounts: Array[Int] = new Array[Int](9)
      val outFormat: Array[Int] = new Array[Int](1)
      var lineStartOffset: Long = 0L
      var linesChecked: Int = 0

      while (lineStartOffset < totalBytes && linesChecked < MaxDetectLines) {
        var scanPos: Long = lineStartOffset
        while (scanPos < totalBytes &&
               headerSegment.get(ValueLayout.JAVA_BYTE, scanPos) != '\n'.toByte &&
               headerSegment.get(ValueLayout.JAVA_BYTE, scanPos) != '\r'.toByte) {
          scanPos += 1L
        }
        val lineByteLength: Long = scanPos - lineStartOffset
        if (lineByteLength >= 4L) {
          val sliceLen: Long = math.min(MaxLineScanBytes, lineByteLength)
          val lineSlice: MemorySegment = headerSegment.asSlice(lineStartOffset, sliceLen)
          outFormat(0) = FormatUnknown
          parseInternal(lineSlice, FormatUnknown, outFormat)
          val matchedFormat: Int = outFormat(0)
          if (matchedFormat > FormatUnknown && matchedFormat < formatCounts.length) {
            formatCounts(matchedFormat) += 1
          }
          linesChecked += 1
        }
        if (scanPos < totalBytes && headerSegment.get(ValueLayout.JAVA_BYTE, scanPos) == '\r'.toByte) {
          scanPos += 1L
        }
        if (scanPos < totalBytes && headerSegment.get(ValueLayout.JAVA_BYTE, scanPos) == '\n'.toByte) {
          scanPos += 1L
        }
        lineStartOffset = scanPos
      }

      var bestFormat: Int = FormatUnknown
      var bestCount: Int = 0
      var formatIndex: Int = 1
      while (formatIndex < formatCounts.length) {
        if (formatCounts(formatIndex) > bestCount) {
          bestCount = formatCounts(formatIndex)
          bestFormat = formatIndex
        }
        formatIndex += 1
      }
      bestFormat
    }
  }

  private def effectiveLineLength(segment: MemorySegment): Long = {
    val maxBytes: Long = math.min(segment.byteSize(), MaxLineScanBytes)
    var pos: Long = 0L
    var foundNewline: Boolean = false
    while (pos < maxBytes && !foundNewline) {
      val byteVal: Byte = segment.get(ValueLayout.JAVA_BYTE, pos)
      if (byteVal == '\n'.toByte || byteVal == '\r'.toByte) {
        foundNewline = true
      } else {
        pos += 1L
      }
    }
    pos
  }

  private def parseInternal(segment: MemorySegment, requiredFormat: Int, outFormat: Array[Int]): Option[Long] = {
    if (segment == MemorySegment.NULL || segment.byteSize() < 4L) {
      None
    } else {
      val lineLength: Long = effectiveLineLength(segment)
      if (lineLength < 4L) {
        None
      } else {
        // 1. Fast path: try parsing directly at offset 0
        val directResult = tryParseAtOffset(segment, 0L, lineLength, requiredFormat, outFormat)
        if (directResult.isDefined) {
          directResult
        } else {
          // 2. Scan for candidate token start positions within the first 48 bytes of this line
          val maxScanOffset: Long = math.min(lineLength - 4L, 48L)
          var offset: Long = 1L
          var foundMicros: Option[Long] = None

          while (offset <= maxScanOffset && foundMicros.isEmpty) {
            val candidateByte: Byte = segment.get(ValueLayout.JAVA_BYTE, offset)
            val prevByte: Byte = segment.get(ValueLayout.JAVA_BYTE, offset - 1L)
            val isGlogPrefix: Boolean = offset == 1L && isDigit(candidateByte) &&
              (prevByte == 'I'.toByte || prevByte == 'W'.toByte || prevByte == 'E'.toByte || prevByte == 'F'.toByte)
            val isColonKeyBoundary: Boolean = prevByte == ':'.toByte && offset >= 2L &&
              !isDigit(segment.get(ValueLayout.JAVA_BYTE, offset - 2L))
            val isTokenStart: Boolean = isGlogPrefix || isColonKeyBoundary ||
              prevByte == ' '.toByte || prevByte == '\t'.toByte || prevByte == '['.toByte ||
              prevByte == '('.toByte || prevByte == '"'.toByte || prevByte == '\''.toByte ||
              prevByte == '<'.toByte || prevByte == '{'.toByte || prevByte == '='.toByte ||
              prevByte == ','.toByte

            if (isTokenStart && (isDigit(candidateByte) || isAlpha(candidateByte))) {
              val parsed = tryParseAtOffset(segment, offset, lineLength - offset, requiredFormat, outFormat)
              if (parsed.isDefined) {
                foundMicros = parsed
              }
            }
            offset += 1L
          }

          foundMicros
        }
      }
    }
  }

  private def recordMatchedFormat(outFormat: Array[Int], formatId: Int): Unit = {
    if (outFormat != null) {
      outFormat(0) = formatId
    }
  }

  private def tryParseAtOffset(
    segment: MemorySegment,
    offset: Long,
    available: Long,
    requiredFormat: Int,
    outFormat: Array[Int]
  ): Option[Long] = {
    val effectiveOffset: Long = if (available > 0L && (segment.get(ValueLayout.JAVA_BYTE, offset) == '['.toByte || segment.get(ValueLayout.JAVA_BYTE, offset) == '('.toByte)) {
      offset + 1L
    } else {
      offset
    }
    val effectiveAvailable: Long = available - (effectiveOffset - offset)

    if (effectiveAvailable < 4L) {
      None
    } else if (requiredFormat != FormatUnknown) {
      requiredFormat match {
        case FormatIsoOrDotted => tryParseIsoOrDotted(segment, effectiveOffset, effectiveAvailable)
        case FormatTwoDigitYear => tryParseTwoDigitYear(segment, effectiveOffset, effectiveAvailable)
        case FormatUsOrEurDate => tryParseUsOrEurDate(segment, effectiveOffset, effectiveAvailable)
        case FormatApacheClf => tryParseApacheClf(segment, effectiveOffset, effectiveAvailable)
        case FormatSyslog => tryParseSyslog(segment, effectiveOffset, effectiveAvailable)
        case FormatMonthDayTime => tryParseMonthDayTime(segment, effectiveOffset, effectiveAvailable)
        case FormatTimeOfDay => tryParseTimeOfDay(segment, effectiveOffset, effectiveAvailable)
        case FormatUnixEpoch =>
          if (isLeadingOrTimestampKey(segment, offset)) {
            tryParseEpoch(segment, effectiveOffset, effectiveAvailable)
          } else {
            None
          }
        case _ => None
      }
    } else {
      val leadingByte: Byte = segment.get(ValueLayout.JAVA_BYTE, effectiveOffset)
      if (isDigit(leadingByte)) {
        val isoResult = tryParseIsoOrDotted(segment, effectiveOffset, effectiveAvailable)
        if (isoResult.isDefined) {
          recordMatchedFormat(outFormat, FormatIsoOrDotted)
          isoResult
        } else {
          val twoDigitResult = tryParseTwoDigitYear(segment, effectiveOffset, effectiveAvailable)
          if (twoDigitResult.isDefined) {
            recordMatchedFormat(outFormat, FormatTwoDigitYear)
            twoDigitResult
          } else {
            val usEurResult = tryParseUsOrEurDate(segment, effectiveOffset, effectiveAvailable)
            if (usEurResult.isDefined) {
              recordMatchedFormat(outFormat, FormatUsOrEurDate)
              usEurResult
            } else {
              val clfResult = tryParseApacheClf(segment, effectiveOffset, effectiveAvailable)
              if (clfResult.isDefined) {
                recordMatchedFormat(outFormat, FormatApacheClf)
                clfResult
              } else {
                val monthDayResult = tryParseMonthDayTime(segment, effectiveOffset, effectiveAvailable)
                if (monthDayResult.isDefined) {
                  recordMatchedFormat(outFormat, FormatMonthDayTime)
                  monthDayResult
                } else {
                  val timeOfDayResult = tryParseTimeOfDay(segment, effectiveOffset, effectiveAvailable)
                  if (timeOfDayResult.isDefined) {
                    recordMatchedFormat(outFormat, FormatTimeOfDay)
                    timeOfDayResult
                  } else if (isLeadingOrTimestampKey(segment, offset)) {
                    val epochResult = tryParseEpoch(segment, effectiveOffset, effectiveAvailable)
                    if (epochResult.isDefined) {
                      recordMatchedFormat(outFormat, FormatUnixEpoch)
                    }
                    epochResult
                  } else {
                    None
                  }
                }
              }
            }
          }
        }
      } else if (isAlpha(leadingByte)) {
        val syslogResult = tryParseSyslog(segment, effectiveOffset, effectiveAvailable)
        if (syslogResult.isDefined) {
          recordMatchedFormat(outFormat, FormatSyslog)
        }
        syslogResult
      } else {
        None
      }
    }
  }

  private def isLeadingOrTimestampKey(segment: MemorySegment, tokenOffset: Long): Boolean = {
    if (tokenOffset <= 0L) {
      true
    } else {
      var allLeadingPunctuation: Boolean = true
      var scanOffset: Long = 0L
      while (scanOffset < tokenOffset && allLeadingPunctuation) {
        val byteVal: Byte = segment.get(ValueLayout.JAVA_BYTE, scanOffset)
        if (byteVal != ' '.toByte && byteVal != '\t'.toByte && byteVal != '-'.toByte &&
            byteVal != '['.toByte && byteVal != '('.toByte && byteVal != '<'.toByte) {
          allLeadingPunctuation = false
        }
        scanOffset += 1L
      }

      if (allLeadingPunctuation) {
        true
      } else {
        // Walk backwards from tokenOffset skipping spaces/tabs, quotes, and '=' or ':'
        var cursor: Long = tokenOffset - 1L
        while (cursor >= 0L && (segment.get(ValueLayout.JAVA_BYTE, cursor) == ' '.toByte ||
               segment.get(ValueLayout.JAVA_BYTE, cursor) == '\t'.toByte ||
               segment.get(ValueLayout.JAVA_BYTE, cursor) == '"'.toByte ||
               segment.get(ValueLayout.JAVA_BYTE, cursor) == '\''.toByte)) {
          cursor -= 1L
        }

        if (cursor < 0L) {
          false
        } else {
          val sepByte: Byte = segment.get(ValueLayout.JAVA_BYTE, cursor)
          if (sepByte != '='.toByte && sepByte != ':'.toByte) {
            false
          } else {
            cursor -= 1L
            while (cursor >= 0L && (segment.get(ValueLayout.JAVA_BYTE, cursor) == ' '.toByte ||
                   segment.get(ValueLayout.JAVA_BYTE, cursor) == '\t'.toByte ||
                   segment.get(ValueLayout.JAVA_BYTE, cursor) == '"'.toByte ||
                   segment.get(ValueLayout.JAVA_BYTE, cursor) == '\''.toByte)) {
              cursor -= 1L
            }

            if (cursor < 0L) {
              false
            } else {
              val keyEndExclusive: Long = cursor + 1L
              while (cursor >= 0L && (isAlpha(segment.get(ValueLayout.JAVA_BYTE, cursor)) ||
                     segment.get(ValueLayout.JAVA_BYTE, cursor) == '@'.toByte ||
                     segment.get(ValueLayout.JAVA_BYTE, cursor) == '_'.toByte)) {
                cursor -= 1L
              }
              val keyStart: Long = cursor + 1L
              val keyLength: Int = (keyEndExclusive - keyStart).toInt
              matchesTimestampKey(segment, keyStart, keyLength)
            }
          }
        }
      }
    }
  }

  private def matchesTimestampKey(segment: MemorySegment, keyStart: Long, keyLength: Int): Boolean = {
    if (keyLength == 2) {
      val c0 = (segment.get(ValueLayout.JAVA_BYTE, keyStart) | 0x20).toChar
      val c1 = (segment.get(ValueLayout.JAVA_BYTE, keyStart + 1L) | 0x20).toChar
      c0 == 't' && c1 == 's'
    } else if (keyLength == 4) {
      val c0 = (segment.get(ValueLayout.JAVA_BYTE, keyStart) | 0x20).toChar
      val c1 = (segment.get(ValueLayout.JAVA_BYTE, keyStart + 1L) | 0x20).toChar
      val c2 = (segment.get(ValueLayout.JAVA_BYTE, keyStart + 2L) | 0x20).toChar
      val c3 = (segment.get(ValueLayout.JAVA_BYTE, keyStart + 3L) | 0x20).toChar
      (c0 == 't' && c1 == 'i' && c2 == 'm' && c3 == 'e') ||
        (c0 == 'd' && c1 == 'a' && c2 == 't' && c3 == 'e')
    } else if (keyLength == 5) {
      val c0 = (segment.get(ValueLayout.JAVA_BYTE, keyStart) | 0x20).toChar
      val c1 = (segment.get(ValueLayout.JAVA_BYTE, keyStart + 1L) | 0x20).toChar
      val c2 = (segment.get(ValueLayout.JAVA_BYTE, keyStart + 2L) | 0x20).toChar
      val c3 = (segment.get(ValueLayout.JAVA_BYTE, keyStart + 3L) | 0x20).toChar
      val c4 = (segment.get(ValueLayout.JAVA_BYTE, keyStart + 4L) | 0x20).toChar
      c0 == 'e' && c1 == 'p' && c2 == 'o' && c3 == 'c' && c4 == 'h'
    } else if (keyLength == 9) {
      matchesAsciiLower(segment, keyStart, "timestamp")
    } else if (keyLength == 10) {
      segment.get(ValueLayout.JAVA_BYTE, keyStart) == '@'.toByte &&
        matchesAsciiLower(segment, keyStart + 1L, "timestamp")
    } else {
      false
    }
  }

  private def matchesAsciiLower(segment: MemorySegment, offset: Long, expectedLower: String): Boolean = {
    var index: Int = 0
    var matches: Boolean = true
    val len: Int = expectedLower.length
    while (index < len && matches) {
      val charVal: Char = (segment.get(ValueLayout.JAVA_BYTE, offset + index.toLong) | 0x20).toChar
      if (charVal != expectedLower.charAt(index)) {
        matches = false
      }
      index += 1
    }
    matches
  }

  /**
   * Parses time-of-day timestamps: `HH:mm:ss[.S..|,S..]` (e.g. `00:00:00.000` through `24:00:00.00`).
   * Anchors to reference date 2026-01-01 (UTC) with zero heap allocations.
   */
  private def tryParseTimeOfDay(segment: MemorySegment, offset: Long, available: Long): Option[Long] = {
    tryParseTimeOfDayCore(segment, offset, available, checkLeadingBoundary = true)
  }

  private def tryParseTimeOfDayCore(
    segment: MemorySegment,
    offset: Long,
    available: Long,
    checkLeadingBoundary: Boolean
  ): Option[Long] = {
    if (available < 8L) {
      None
    } else if (checkLeadingBoundary && offset > 0L && {
      val prevByte = segment.get(ValueLayout.JAVA_BYTE, offset - 1L)
      prevByte == ':'.toByte || prevByte == '.'.toByte || prevByte == '/'.toByte || isDigit(prevByte)
    }) {
      None
    } else {
      val h0 = segment.get(ValueLayout.JAVA_BYTE, offset + 0L)
      val h1 = segment.get(ValueLayout.JAVA_BYTE, offset + 1L)
      val sep1 = segment.get(ValueLayout.JAVA_BYTE, offset + 2L)
      val m0 = segment.get(ValueLayout.JAVA_BYTE, offset + 3L)
      val m1 = segment.get(ValueLayout.JAVA_BYTE, offset + 4L)
      val sep2 = segment.get(ValueLayout.JAVA_BYTE, offset + 5L)
      val s0 = segment.get(ValueLayout.JAVA_BYTE, offset + 6L)
      val s1 = segment.get(ValueLayout.JAVA_BYTE, offset + 7L)

      if (isDigit(h0) && isDigit(h1) && sep1 == ':'.toByte &&
          isDigit(m0) && isDigit(m1) && sep2 == ':'.toByte &&
          isDigit(s0) && isDigit(s1)) {
        val hour = parseDigits2(h0, h1)
        val minute = parseDigits2(m0, m1)
        val second = parseDigits2(s0, s1)
        val isValidTime: Boolean = (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59 && second >= 0 && second <= 59) ||
          (hour == 24 && minute >= 0 && minute <= 59 && second >= 0 && second <= 59)

        if (isValidTime) {
          val limit: Long = offset + available
          val packedFrac: Long = parseFractionalPacked(segment, offset + 8L, limit)
          if (packedFrac < 0L) {
            None
          } else {
            val microsecondFraction: Long = packedFrac & 0xFFFFFFFFL
            val totalSecondsInDay: Long = (hour.toLong * 3600L) + (minute.toLong * 60L) + second.toLong
            Some(ReferenceDateEpochMicros + (totalSecondsInDay * MicrosPerSecond) + microsecondFraction)
          }
        } else {
          None
        }
      } else {
        None
      }
    }
  }

  /**
   * Parses month-day timestamps: `MM-dd HH:mm:ss[.S..]`, `MM/dd HH:mm:ss[.S..]`,
   * or Google glog/klog `MMdd HH:mm:ss[.S..]` preceded by `[IWEF]` at offset 0.
   */
  private def tryParseMonthDayTime(segment: MemorySegment, offset: Long, available: Long): Option[Long] = {
    if (offset == 1L && available >= 13L && {
      val levelByte = segment.get(ValueLayout.JAVA_BYTE, 0L)
      levelByte == 'I'.toByte || levelByte == 'W'.toByte || levelByte == 'E'.toByte || levelByte == 'F'.toByte
    }) {
      val m0 = segment.get(ValueLayout.JAVA_BYTE, offset + 0L)
      val m1 = segment.get(ValueLayout.JAVA_BYTE, offset + 1L)
      val d0 = segment.get(ValueLayout.JAVA_BYTE, offset + 2L)
      val d1 = segment.get(ValueLayout.JAVA_BYTE, offset + 3L)
      val sepDateTime = segment.get(ValueLayout.JAVA_BYTE, offset + 4L)
      if (isDigit(m0) && isDigit(m1) && isDigit(d0) && isDigit(d1) && sepDateTime == ' '.toByte) {
        val month = parseDigits2(m0, m1)
        val day = parseDigits2(d0, d1)
        if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
          tryParseTimeOfDayCore(segment, offset + 5L, available - 5L, checkLeadingBoundary = false) match {
            case Some(timeMicros) => combineDateWithTimeOffsetMicros(2026, month, day, timeMicros - ReferenceDateEpochMicros)
            case None => None
          }
        } else {
          None
        }
      } else {
        None
      }
    } else if (available < 14L) {
      None
    } else if (offset > 0L && !isMonthDayLeadingDelimiter(segment.get(ValueLayout.JAVA_BYTE, offset - 1L))) {
      None
    } else {
      val m0 = segment.get(ValueLayout.JAVA_BYTE, offset + 0L)
      val m1 = segment.get(ValueLayout.JAVA_BYTE, offset + 1L)
      val sep1 = segment.get(ValueLayout.JAVA_BYTE, offset + 2L)
      val d0 = segment.get(ValueLayout.JAVA_BYTE, offset + 3L)
      val d1 = segment.get(ValueLayout.JAVA_BYTE, offset + 4L)
      val sepDateTime = segment.get(ValueLayout.JAVA_BYTE, offset + 5L)
      val isDateSep = sep1 == '-'.toByte || sep1 == '/'.toByte
      val isDateTimeSep = sepDateTime == ' '.toByte || sepDateTime == 'T'.toByte

      if (isDigit(m0) && isDigit(m1) && isDateSep && isDigit(d0) && isDigit(d1) && isDateTimeSep) {
        val month = parseDigits2(m0, m1)
        val day = parseDigits2(d0, d1)
        if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
          tryParseTimeOfDayCore(segment, offset + 6L, available - 6L, checkLeadingBoundary = false) match {
            case Some(timeMicros) =>
              combineDateWithTimeOffsetMicros(2026, month, day, timeMicros - ReferenceDateEpochMicros)
            case None => None
          }
        } else {
          None
        }
      } else {
        None
      }
    }
  }

  private inline def isMonthDayLeadingDelimiter(byteVal: Byte): Boolean = {
    byteVal == ' '.toByte || byteVal == '\t'.toByte || byteVal == '['.toByte ||
      byteVal == '('.toByte || byteVal == '"'.toByte || byteVal == '\''.toByte ||
      byteVal == '='.toByte || byteVal == ','.toByte
  }

  /** Parses two-digit year timestamps: `yy-MM-dd HH:mm:ss` or `yy/MM/dd HH:mm:ss` (e.g. `24-10-09 14:23:01`). */
  private def tryParseTwoDigitYear(segment: MemorySegment, offset: Long, available: Long): Option[Long] = {
    if (available < 17L) {
      None
    } else if (offset > 0L && !isMonthDayLeadingDelimiter(segment.get(ValueLayout.JAVA_BYTE, offset - 1L))) {
      None
    } else {
      val y0 = segment.get(ValueLayout.JAVA_BYTE, offset + 0L)
      val y1 = segment.get(ValueLayout.JAVA_BYTE, offset + 1L)
      val sep1 = segment.get(ValueLayout.JAVA_BYTE, offset + 2L)
      val m0 = segment.get(ValueLayout.JAVA_BYTE, offset + 3L)
      val m1 = segment.get(ValueLayout.JAVA_BYTE, offset + 4L)
      val sep2 = segment.get(ValueLayout.JAVA_BYTE, offset + 5L)
      val d0 = segment.get(ValueLayout.JAVA_BYTE, offset + 6L)
      val d1 = segment.get(ValueLayout.JAVA_BYTE, offset + 7L)
      val sepDateTime = segment.get(ValueLayout.JAVA_BYTE, offset + 8L)

      val isDateSepValid = (sep1 == sep2) && (sep1 == '-'.toByte || sep1 == '/'.toByte || sep1 == '.'.toByte)
      val isDateTimeSep = sepDateTime == ' '.toByte || sepDateTime == 'T'.toByte || sepDateTime == '_'.toByte

      if (isDigit(y0) && isDigit(y1) && isDateSepValid && isDigit(m0) && isDigit(m1) && isDigit(d0) && isDigit(d1) && isDateTimeSep) {
        val twoDigitYear = parseDigits2(y0, y1)
        val month = parseDigits2(m0, m1)
        val day = parseDigits2(d0, d1)
        val year = if (twoDigitYear >= 70) { 1900 + twoDigitYear } else { 2000 + twoDigitYear }
        if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
          tryParseTimeOfDayCore(segment, offset + 9L, available - 9L, checkLeadingBoundary = false) match {
            case Some(timeMicros) =>
              combineDateWithTimeOffsetMicros(year, month, day, timeMicros - ReferenceDateEpochMicros)
            case None => None
          }
        } else {
          None
        }
      } else {
        None
      }
    }
  }

  /** Parses US/European dates: `MM/dd/yyyy HH:mm:ss` or `dd/MM/yyyy HH:mm:ss` or `dd-MM-yyyy HH:mm:ss`. */
  private def tryParseUsOrEurDate(segment: MemorySegment, offset: Long, available: Long): Option[Long] = {
    if (available < 19L) {
      None
    } else {
      val a0 = segment.get(ValueLayout.JAVA_BYTE, offset + 0L)
      val a1 = segment.get(ValueLayout.JAVA_BYTE, offset + 1L)
      val sep1 = segment.get(ValueLayout.JAVA_BYTE, offset + 2L)
      val b0 = segment.get(ValueLayout.JAVA_BYTE, offset + 3L)
      val b1 = segment.get(ValueLayout.JAVA_BYTE, offset + 4L)
      val sep2 = segment.get(ValueLayout.JAVA_BYTE, offset + 5L)
      val y0 = segment.get(ValueLayout.JAVA_BYTE, offset + 6L)
      val y1 = segment.get(ValueLayout.JAVA_BYTE, offset + 7L)
      val y2 = segment.get(ValueLayout.JAVA_BYTE, offset + 8L)
      val y3 = segment.get(ValueLayout.JAVA_BYTE, offset + 9L)
      val sepDateTime = segment.get(ValueLayout.JAVA_BYTE, offset + 10L)

      val isDateSepValid = (sep1 == sep2) && (sep1 == '/'.toByte || sep1 == '-'.toByte || sep1 == '.'.toByte)
      val isDateTimeSep = sepDateTime == ' '.toByte || sepDateTime == 'T'.toByte

      if (isDigit(a0) && isDigit(a1) && isDateSepValid && isDigit(b0) && isDigit(b1) &&
          isDigit(y0) && isDigit(y1) && isDigit(y2) && isDigit(y3) && isDateTimeSep) {
        val partA = parseDigits2(a0, a1)
        val partB = parseDigits2(b0, b1)
        val year = parseDigits4(y0, y1, y2, y3)
        if (year >= 1970 && year <= 2100) {
          val (month, day) = if (partA >= 1 && partA <= 12 && partB >= 1 && partB <= 31) {
            (partA, partB) // MM/dd/yyyy
          } else if (partB >= 1 && partB <= 12 && partA >= 1 && partA <= 31) {
            (partB, partA) // dd/MM/yyyy
          } else {
            (-1, -1)
          }
          if (month > 0 && day > 0) {
            tryParseTimeOfDayCore(segment, offset + 11L, available - 11L, checkLeadingBoundary = false) match {
              case Some(timeMicros) =>
                combineDateWithTimeOffsetMicros(year, month, day, timeMicros - ReferenceDateEpochMicros)
              case None => None
            }
          } else {
            None
          }
        } else {
          None
        }
      } else {
        None
      }
    }
  }

  /**
   * Parses ISO-8601, RFC 3339, Dotted/Slashed, or Compact 8-digit timestamps:
   * `YYYY-MM-DD[ T_-]HH:mm:ss[.S..][Z|+00:00]`
   * `YYYY.MM.DD[ T_-]HH:mm:ss[.S..]`
   * `YYYY/MM/DD[ T_-]HH:mm:ss[.S..]`
   * `YYYYMMDD[ T_]HH:mm:ss[.S..]`
   */
  private def tryParseIsoOrDotted(segment: MemorySegment, offset: Long, available: Long): Option[Long] = {
    if (available >= 19L) {
      val b0 = segment.get(ValueLayout.JAVA_BYTE, offset + 0L)
      val b1 = segment.get(ValueLayout.JAVA_BYTE, offset + 1L)
      val b2 = segment.get(ValueLayout.JAVA_BYTE, offset + 2L)
      val b3 = segment.get(ValueLayout.JAVA_BYTE, offset + 3L)

      val sep1 = segment.get(ValueLayout.JAVA_BYTE, offset + 4L)
      val sep2 = segment.get(ValueLayout.JAVA_BYTE, offset + 7L)
      val sepDateTime = segment.get(ValueLayout.JAVA_BYTE, offset + 10L)
      val sep3 = segment.get(ValueLayout.JAVA_BYTE, offset + 13L)
      val sep4 = segment.get(ValueLayout.JAVA_BYTE, offset + 16L)

      val isDateSepValid = (sep1 == sep2) && (sep1 == '-'.toByte || sep1 == '.'.toByte || sep1 == '/'.toByte)
      val isTimeSepValid = (sep3 == sep4) && (sep3 == ':'.toByte || (sep1 != '.'.toByte && (sep3 == '.'.toByte || sep3 == '-'.toByte)))
      val isDateTimeSep = sepDateTime == ' '.toByte || sepDateTime == 'T'.toByte || sepDateTime == '-'.toByte || sepDateTime == '_'.toByte

      if (isDigit(b0) && isDigit(b1) && isDigit(b2) && isDigit(b3) &&
          isDateSepValid && isDateTimeSep && isTimeSepValid) {
        val m0 = segment.get(ValueLayout.JAVA_BYTE, offset + 5L)
        val m1 = segment.get(ValueLayout.JAVA_BYTE, offset + 6L)
        val d0 = segment.get(ValueLayout.JAVA_BYTE, offset + 8L)
        val d1 = segment.get(ValueLayout.JAVA_BYTE, offset + 9L)
        val h0 = segment.get(ValueLayout.JAVA_BYTE, offset + 11L)
        val h1 = segment.get(ValueLayout.JAVA_BYTE, offset + 12L)
        val min0 = segment.get(ValueLayout.JAVA_BYTE, offset + 14L)
        val min1 = segment.get(ValueLayout.JAVA_BYTE, offset + 15L)
        val s0 = segment.get(ValueLayout.JAVA_BYTE, offset + 17L)
        val s1 = segment.get(ValueLayout.JAVA_BYTE, offset + 18L)

        if (isDigit(m0) && isDigit(m1) && isDigit(d0) && isDigit(d1) &&
            isDigit(h0) && isDigit(h1) && isDigit(min0) && isDigit(min1) && isDigit(s0) && isDigit(s1)) {
          val year: Int = parseDigits4(b0, b1, b2, b3)
          val month: Int = parseDigits2(m0, m1)
          val day: Int = parseDigits2(d0, d1)
          val hour: Int = parseDigits2(h0, h1)
          val minute: Int = parseDigits2(min0, min1)
          val second: Int = parseDigits2(s0, s1)
          val isValidHour: Boolean = (hour >= 0 && hour <= 23) || (hour == 24 && minute == 0 && second == 0)

          if (year >= 1970 && year <= 2100 &&
              month >= 1 && month <= 12 &&
              day >= 1 && day <= 31 &&
              isValidHour &&
              minute >= 0 && minute <= 59 &&
              second >= 0 && second <= 59) {
            val packedFrac: Long = parseFractionalPacked(segment, offset + 19L, offset + available)
            if (packedFrac < 0L) {
              None
            } else {
              toEpochMicrosUtc(year, month, day, hour, minute, second, packedFrac & 0xFFFFFFFFL)
            }
          } else {
            None
          }
        } else {
          None
        }
      } else {
        tryParseCompactDate(segment, offset, available)
      }
    } else if (available >= 17L) {
      tryParseCompactDate(segment, offset, available)
    } else {
      None
    }
  }

  private def tryParseCompactDate(segment: MemorySegment, offset: Long, available: Long): Option[Long] = {
    if (available < 17L) {
      None
    } else {
      val b0 = segment.get(ValueLayout.JAVA_BYTE, offset + 0L)
      val b1 = segment.get(ValueLayout.JAVA_BYTE, offset + 1L)
      val b2 = segment.get(ValueLayout.JAVA_BYTE, offset + 2L)
      val b3 = segment.get(ValueLayout.JAVA_BYTE, offset + 3L)
      val m0 = segment.get(ValueLayout.JAVA_BYTE, offset + 4L)
      val m1 = segment.get(ValueLayout.JAVA_BYTE, offset + 5L)
      val d0 = segment.get(ValueLayout.JAVA_BYTE, offset + 6L)
      val d1 = segment.get(ValueLayout.JAVA_BYTE, offset + 7L)
      val sepDateTime = segment.get(ValueLayout.JAVA_BYTE, offset + 8L)
      val isDateTimeSep = sepDateTime == ' '.toByte || sepDateTime == 'T'.toByte || sepDateTime == '_'.toByte

      if (isDigit(b0) && isDigit(b1) && isDigit(b2) && isDigit(b3) &&
          isDigit(m0) && isDigit(m1) && isDigit(d0) && isDigit(d1) && isDateTimeSep) {
        val year = parseDigits4(b0, b1, b2, b3)
        val month = parseDigits2(m0, m1)
        val day = parseDigits2(d0, d1)
        if (year >= 1970 && year <= 2100 && month >= 1 && month <= 12 && day >= 1 && day <= 31) {
          tryParseTimeOfDayCore(segment, offset + 9L, available - 9L, checkLeadingBoundary = false) match {
            case Some(timeMicros) =>
              combineDateWithTimeOffsetMicros(year, month, day, timeMicros - ReferenceDateEpochMicros)
            case None => None
          }
        } else {
          None
        }
      } else {
        None
      }
    }
  }

  /**
   * Parses Unix Epoch timestamps (seconds: 10 digits, millis: 13 digits, micros: 16 digits, nanos: 19 digits, or float seconds).
   * Range: 946684800 (Jan 1, 2000) to 4102444800 (Jan 1, 2100).
   */
  private def tryParseEpoch(segment: MemorySegment, offset: Long, available: Long): Option[Long] = {
    var digitCount: Int = 0
    while (digitCount < available && digitCount < 20 && isDigit(segment.get(ValueLayout.JAVA_BYTE, offset + digitCount.toLong))) {
      digitCount += 1
    }

    val limit: Long = offset + available
    if (digitCount == 10) {
      // 10-digit epoch seconds: e.g. 1131523501
      val epochSec = parseLongDigits(segment, offset, 10)
      if (epochSec >= 946684800L && epochSec <= 4102444800L) {
        val packedFrac: Long = parseFractionalPacked(segment, offset + 10L, limit)
        if (packedFrac >= 0L) {
          Some((epochSec * MicrosPerSecond) + (packedFrac & 0xFFFFFFFFL))
        } else {
          None
        }
      } else {
        None
      }
    } else if (digitCount == 13) {
      val epochMillis = parseLongDigits(segment, offset, 13)
      if (epochMillis >= 946684800000L && epochMillis <= 4102444800000L &&
          isTokenEndDelimiter(segment, offset + 13L, limit)) {
        Some(epochMillis * 1000L)
      } else {
        None
      }
    } else if (digitCount == 16) {
      val epochMicros = parseLongDigits(segment, offset, 16)
      if (epochMicros >= 946684800000000L && epochMicros <= 4102444800000000L &&
          isTokenEndDelimiter(segment, offset + 16L, limit)) {
        Some(epochMicros)
      } else {
        None
      }
    } else if (digitCount == 19) {
      val epochNanos = parseLongDigits(segment, offset, 19)
      if (epochNanos >= 946684800000000000L && epochNanos <= 4102444800000000000L &&
          isTokenEndDelimiter(segment, offset + 19L, limit)) {
        Some(epochNanos / 1000L)
      } else {
        None
      }
    } else {
      None
    }
  }

  /**
   * Parses Syslog RFC 3164 / BSD timestamp: `MMM dd HH:mm:ss[.S..]` or `MMM  d HH:mm:ss[.S..]`.
   * Requires uppercase first letter to avoid matching lowercase English words such as `may`.
   */
  private def tryParseSyslog(segment: MemorySegment, offset: Long, available: Long): Option[Long] = {
    if (available < 15L) {
      None
    } else {
      val m0 = segment.get(ValueLayout.JAVA_BYTE, offset + 0L)
      val m1 = segment.get(ValueLayout.JAVA_BYTE, offset + 1L)
      val m2 = segment.get(ValueLayout.JAVA_BYTE, offset + 2L)
      val isUpperInitial = m0 >= 'A'.toByte && m0 <= 'Z'.toByte
      val month = if (isUpperInitial) { parseMonthAbbr(m0, m1, m2) } else { 0 }

      if (month >= 1 && month <= 12 && segment.get(ValueLayout.JAVA_BYTE, offset + 3L) == ' '.toByte) {
        val d0 = segment.get(ValueLayout.JAVA_BYTE, offset + 4L)
        val d1 = segment.get(ValueLayout.JAVA_BYTE, offset + 5L)
        val day = if (d0 == ' '.toByte && isDigit(d1)) {
          (d1 - '0'.toByte).toInt
        } else if (isDigit(d0) && isDigit(d1)) {
          parseDigits2(d0, d1)
        } else {
          -1
        }

        if (day >= 1 && day <= 31 && segment.get(ValueLayout.JAVA_BYTE, offset + 6L) == ' '.toByte) {
          tryParseTimeOfDayCore(segment, offset + 7L, available - 7L, checkLeadingBoundary = false) match {
            case Some(timeMicros) =>
              val year = extractYearBeforeOffset(segment, offset, 2026)
              combineDateWithTimeOffsetMicros(year, month, day, timeMicros - ReferenceDateEpochMicros)
            case None => None
          }
        } else {
          None
        }
      } else {
        None
      }
    }
  }

  /**
   * Parses Apache CLF / Nginx / Tomcat timestamp: `dd/MMM/yyyy:HH:mm:ss[.S..]` or `dd-MMM-yyyy HH:mm:ss[.S..]`.
   */
  private def tryParseApacheClf(segment: MemorySegment, offset: Long, available: Long): Option[Long] = {
    if (available < 20L) {
      None
    } else {
      val d0 = segment.get(ValueLayout.JAVA_BYTE, offset + 0L)
      val d1 = segment.get(ValueLayout.JAVA_BYTE, offset + 1L)
      val sep1 = segment.get(ValueLayout.JAVA_BYTE, offset + 2L)
      val m0 = segment.get(ValueLayout.JAVA_BYTE, offset + 3L)
      val m1 = segment.get(ValueLayout.JAVA_BYTE, offset + 4L)
      val m2 = segment.get(ValueLayout.JAVA_BYTE, offset + 5L)
      val sep2 = segment.get(ValueLayout.JAVA_BYTE, offset + 6L)
      val y0 = segment.get(ValueLayout.JAVA_BYTE, offset + 7L)
      val y1 = segment.get(ValueLayout.JAVA_BYTE, offset + 8L)
      val y2 = segment.get(ValueLayout.JAVA_BYTE, offset + 9L)
      val y3 = segment.get(ValueLayout.JAVA_BYTE, offset + 10L)
      val sep3 = segment.get(ValueLayout.JAVA_BYTE, offset + 11L)

      val isDateSepValid = (sep1 == sep2) && (sep1 == '/'.toByte || sep1 == '-'.toByte)
      val isDateTimeSep = sep3 == ':'.toByte || sep3 == ' '.toByte || sep3 == 'T'.toByte

      if (isDigit(d0) && isDigit(d1) && isDateSepValid &&
          isDigit(y0) && isDigit(y1) && isDigit(y2) && isDigit(y3) && isDateTimeSep) {
        val month = parseMonthAbbr(m0, m1, m2)
        if (month >= 1 && month <= 12) {
          val day = parseDigits2(d0, d1)
          val year = parseDigits4(y0, y1, y2, y3)
          if (year >= 1970 && year <= 2100 && day >= 1 && day <= 31) {
            tryParseTimeOfDayCore(segment, offset + 12L, available - 12L, checkLeadingBoundary = false) match {
              case Some(timeMicros) =>
                combineDateWithTimeOffsetMicros(year, month, day, timeMicros - ReferenceDateEpochMicros)
              case None => None
            }
          } else {
            None
          }
        } else {
          None
        }
      } else {
        None
      }
    }
  }

  private def toEpochMicrosUtc(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
    microsFraction: Long = 0L
  ): Option[Long] = {
    try {
      val epochDay: Long = LocalDate.of(year, month, day).toEpochDay
      val epochSeconds: Long = (epochDay * SecondsPerDay) + (hour.toLong * 3600L) + (minute.toLong * 60L) + second.toLong
      Some((epochSeconds * MicrosPerSecond) + microsFraction)
    } catch {
      case NonFatal(_) => None
    }
  }

  private def combineDateWithTimeOffsetMicros(
    year: Int,
    month: Int,
    day: Int,
    timeOffsetMicros: Long
  ): Option[Long] = {
    try {
      val epochDay: Long = LocalDate.of(year, month, day).toEpochDay
      Some((epochDay * SecondsPerDay * MicrosPerSecond) + timeOffsetMicros)
    } catch {
      case NonFatal(_) => None
    }
  }

  /**
   * Parses optional fractional seconds (`.` or `,` followed by 1..9 digits) and validates trailing delimiter.
   * Returns packed `(consumedBytes << 32) | microsecondFraction`, or `-1L` if the trailing boundary is invalid.
   */
  private def parseFractionalPacked(
    segment: MemorySegment,
    sepOffset: Long,
    limit: Long
  ): Long = {
    if (sepOffset >= limit) {
      0L
    } else {
      val sepByte: Byte = segment.get(ValueLayout.JAVA_BYTE, sepOffset)
      if (sepByte != '.'.toByte && sepByte != ','.toByte) {
        if (isTokenEndDelimiter(segment, sepOffset, limit)) { 0L } else { -1L }
      } else {
        val firstDigitPos: Long = sepOffset + 1L
        if (firstDigitPos < limit && isDigit(segment.get(ValueLayout.JAVA_BYTE, firstDigitPos))) {
          var digitCount: Int = 0
          var micros: Long = 0L
          var multiplier: Long = 100000L
          while (firstDigitPos + digitCount.toLong < limit &&
                 digitCount < 9 &&
                 isDigit(segment.get(ValueLayout.JAVA_BYTE, firstDigitPos + digitCount.toLong))) {
            val digitValue: Long = (segment.get(ValueLayout.JAVA_BYTE, firstDigitPos + digitCount.toLong) - '0'.toByte).toLong
            if (multiplier > 0L) {
              micros += digitValue * multiplier
              multiplier /= 10L
            }
            digitCount += 1
          }
          val endPos: Long = firstDigitPos + digitCount.toLong
          if (isTokenEndDelimiter(segment, endPos, limit)) {
            ((1L + digitCount.toLong) << 32) | micros
          } else {
            -1L
          }
        } else if (sepByte == ','.toByte && isTokenEndDelimiter(segment, sepOffset, limit)) {
          // Trailing comma used as CSV/JSON field delimiter (e.g. "14:23:01,INFO")
          0L
        } else {
          -1L
        }
      }
    }
  }

  private inline def isTokenEndDelimiter(segment: MemorySegment, pos: Long, limit: Long): Boolean = {
    if (pos >= limit) {
      true
    } else {
      val byteVal: Byte = segment.get(ValueLayout.JAVA_BYTE, pos)
      byteVal == ' '.toByte || byteVal == '\t'.toByte || byteVal == '\r'.toByte ||
        byteVal == '\n'.toByte || byteVal == ']'.toByte || byteVal == ')'.toByte ||
        byteVal == '>'.toByte || byteVal == '}'.toByte || byteVal == '"'.toByte ||
        byteVal == '\''.toByte || byteVal == ','.toByte || byteVal == ';'.toByte ||
        byteVal == '|'.toByte || byteVal == 'Z'.toByte || byteVal == 'z'.toByte ||
        byteVal == '+'.toByte || byteVal == '-'.toByte || byteVal == '_'.toByte
    }
  }

  private def extractYearBeforeOffset(segment: MemorySegment, maxOffset: Long, fallbackYear: Int): Int = {
    if (maxOffset < 4L) {
      fallbackYear
    } else {
      val limit: Long = maxOffset - 4L
      var scanOffset: Long = 0L
      var foundYear: Int = fallbackYear
      var done: Boolean = false
      while (scanOffset <= limit && !done) {
        val b0 = segment.get(ValueLayout.JAVA_BYTE, scanOffset)
        val b1 = segment.get(ValueLayout.JAVA_BYTE, scanOffset + 1L)
        val b2 = segment.get(ValueLayout.JAVA_BYTE, scanOffset + 2L)
        val b3 = segment.get(ValueLayout.JAVA_BYTE, scanOffset + 3L)
        if (isDigit(b0) && isDigit(b1) && isDigit(b2) && isDigit(b3)) {
          val candidateYear = parseDigits4(b0, b1, b2, b3)
          if (candidateYear >= 1970 && candidateYear <= 2100) {
            foundYear = candidateYear
            done = true
          }
        }
        scanOffset += 1L
      }
      foundYear
    }
  }

  private inline def isDigit(byteVal: Byte): Boolean = byteVal >= '0'.toByte && byteVal <= '9'.toByte
  private inline def isAlpha(byteVal: Byte): Boolean = {
    (byteVal >= 'a'.toByte && byteVal <= 'z'.toByte) || (byteVal >= 'A'.toByte && byteVal <= 'Z'.toByte)
  }

  private inline def parseDigits2(b0: Byte, b1: Byte): Int = (b0 - '0'.toByte) * 10 + (b1 - '0'.toByte)
  private inline def parseDigits4(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Int = {
    (b0 - '0'.toByte) * 1000 + (b1 - '0'.toByte) * 100 + (b2 - '0'.toByte) * 10 + (b3 - '0'.toByte)
  }

  private def parseLongDigits(segment: MemorySegment, offset: Long, count: Int): Long = {
    var result: Long = 0L
    var digitIndex: Int = 0
    while (digitIndex < count) {
      val byteVal = segment.get(ValueLayout.JAVA_BYTE, offset + digitIndex.toLong)
      result = result * 10L + (byteVal - '0'.toByte).toLong
      digitIndex += 1
    }
    result
  }

  private inline def parseMonthAbbr(b0: Byte, b1: Byte, b2: Byte): Int = {
    val c0 = (b0 | 0x20).toChar
    val c1 = (b1 | 0x20).toChar
    val c2 = (b2 | 0x20).toChar
    if (c0 == 'j' && c1 == 'a' && c2 == 'n') { 1 }
    else if (c0 == 'f' && c1 == 'e' && c2 == 'b') { 2 }
    else if (c0 == 'm' && c1 == 'a' && c2 == 'r') { 3 }
    else if (c0 == 'a' && c1 == 'p' && c2 == 'r') { 4 }
    else if (c0 == 'm' && c1 == 'a' && c2 == 'y') { 5 }
    else if (c0 == 'j' && c1 == 'u' && c2 == 'n') { 6 }
    else if (c0 == 'j' && c1 == 'u' && c2 == 'l') { 7 }
    else if (c0 == 'a' && c1 == 'u' && c2 == 'g') { 8 }
    else if (c0 == 's' && c1 == 'e' && c2 == 'p') { 9 }
    else if (c0 == 'o' && c1 == 'c' && c2 == 't') { 10 }
    else if (c0 == 'n' && c1 == 'o' && c2 == 'v') { 11 }
    else if (c0 == 'd' && c1 == 'e' && c2 == 'c') { 12 }
    else { 0 }
  }
}
