package umbrix.core.profile

import java.lang.foreign.{MemorySegment, ValueLayout}
import java.nio.charset.StandardCharsets
import umbrix.vfs.LogStorageReader

/**
 * Severity category classification for log records used by the GPU seismograph minimap.
 */
enum LineSeverity {
  case Error
  case Warn
  case Info
  case Normal
}

/**
 * Domain specification for a programming language or log format profile.
 *
 * Defines rules for:
 * 1. Identifying multi-line continuation frames (stack traces, JSON blocks) vs parent event headers.
 * 2. Rapid zero-heap severity classification (Error, Warn, Info) for density binning.
 */
trait LogLanguageProfile {
  def id: String
  def displayName: String

  def isEventHeaderPrefix(segment: MemorySegment, offset: Long, availableLength: Long): Boolean

  def classifyLineSeverity(segment: MemorySegment, offset: Long, availableLength: Long): LineSeverity
}

abstract class BaseLogLanguageProfile(
  val id: String,
  val displayName: String,
  val errorKeywords: Array[Array[Byte]],
  val warnKeywords: Array[Array[Byte]],
  val infoKeywords: Array[Array[Byte]],
  val fallbackErrorKeywords: Array[Array[Byte]] = Array.empty
) extends LogLanguageProfile {

  protected def matchesBytesAt(segment: MemorySegment, offset: Long, pattern: Array[Byte]): Boolean = {
    var matchIndex = 0
    var isMatched = true
    while (matchIndex < pattern.length && isMatched) {
      if (segment.get(ValueLayout.JAVA_BYTE, offset + matchIndex) != pattern(matchIndex)) {
        isMatched = false
      } else {
        matchIndex += 1
      }
    }
    isMatched
  }

  protected def containsKeyword(segment: MemorySegment, offset: Long, length: Long, keyword: Array[Byte]): Boolean = {
    val maxSearchIndex = length - keyword.length
    var searchPosition = 0L
    var found = false
    while (searchPosition <= maxSearchIndex && !found) {
      if (matchesBytesAt(segment, offset + searchPosition, keyword)) {
        found = true
      } else {
        searchPosition += 1L
      }
    }
    found
  }

  def classifyLineSeverity(segment: MemorySegment, offset: Long, availableLength: Long): LineSeverity = {
    if (segment == MemorySegment.NULL || availableLength <= 0L) {
      LineSeverity.Normal
    } else {
      // Check first 256 bytes of the line for performance to avoid scanning large payloads
      val inspectLength = math.min(availableLength, 256L)

      // Tier 1: Check explicit log level tokens first (e.g. " ERROR ", "[ERROR]", " WARN ", " INFO ")
      var detectedSeverity: LineSeverity = LineSeverity.Normal
      var errorIndex = 0
      while (errorIndex < errorKeywords.length && detectedSeverity == LineSeverity.Normal) {
        if (containsKeyword(segment, offset, inspectLength, errorKeywords(errorIndex))) {
          detectedSeverity = LineSeverity.Error
        } else {
          errorIndex += 1
        }
      }

      if (detectedSeverity == LineSeverity.Normal) {
        var warnIndex = 0
        while (warnIndex < warnKeywords.length && detectedSeverity == LineSeverity.Normal) {
          if (containsKeyword(segment, offset, inspectLength, warnKeywords(warnIndex))) {
            detectedSeverity = LineSeverity.Warn
          } else {
            warnIndex += 1
          }
        }
      }

      if (detectedSeverity == LineSeverity.Normal) {
        var infoIndex = 0
        while (infoIndex < infoKeywords.length && detectedSeverity == LineSeverity.Normal) {
          if (containsKeyword(segment, offset, inspectLength, infoKeywords(infoIndex))) {
            detectedSeverity = LineSeverity.Info
          } else {
            infoIndex += 1
          }
        }
      }

      // Tier 2: If no explicit level token was matched, check fallback unformatted exceptions/panics
      if (detectedSeverity == LineSeverity.Normal) {
        var fallbackIndex = 0
        while (fallbackIndex < fallbackErrorKeywords.length && detectedSeverity == LineSeverity.Normal) {
          if (containsKeyword(segment, offset, inspectLength, fallbackErrorKeywords(fallbackIndex))) {
            detectedSeverity = LineSeverity.Error
          } else {
            fallbackIndex += 1
          }
        }
      }

      detectedSeverity
    }
  }

  def classifyLineSeverity(lineText: String): LineSeverity = {
    if (lineText == null || lineText.isEmpty) {
      LineSeverity.Normal
    } else {
      val inspectPrefix = if (lineText.length > 256) lineText.substring(0, 256) else lineText
      val inspectUpper = inspectPrefix.toUpperCase

      if (errorKeywords.exists(k => inspectUpper.contains(new String(k, StandardCharsets.US_ASCII)))) {
        LineSeverity.Error
      } else if (warnKeywords.exists(k => inspectUpper.contains(new String(k, StandardCharsets.US_ASCII)))) {
        LineSeverity.Warn
      } else if (infoKeywords.exists(k => inspectUpper.contains(new String(k, StandardCharsets.US_ASCII)))) {
        LineSeverity.Info
      } else if (fallbackErrorKeywords.exists(k => inspectPrefix.contains(new String(k, StandardCharsets.US_ASCII)))) {
        LineSeverity.Error
      } else {
        LineSeverity.Normal
      }
    }
  }
}

/**
 * Java / JVM Log Profile (Logback, Log4j2, java.util.logging, Spring Boot, Hadoop).
 */
object JavaJvmProfile extends BaseLogLanguageProfile(
  id = "java",
  displayName = "Java / JVM (Logback, Log4j)",
  errorKeywords = Array(
    " ERROR ".getBytes(StandardCharsets.US_ASCII),
    "[ERROR]".getBytes(StandardCharsets.US_ASCII),
    "ERROR:".getBytes(StandardCharsets.US_ASCII),
    " FATAL ".getBytes(StandardCharsets.US_ASCII),
    "[FATAL]".getBytes(StandardCharsets.US_ASCII),
    "FATAL:".getBytes(StandardCharsets.US_ASCII),
    "level=error".getBytes(StandardCharsets.US_ASCII)
  ),
  warnKeywords = Array(
    " WARN ".getBytes(StandardCharsets.US_ASCII),
    "[WARN]".getBytes(StandardCharsets.US_ASCII),
    "WARN:".getBytes(StandardCharsets.US_ASCII),
    " WARNING ".getBytes(StandardCharsets.US_ASCII),
    "[WARNING]".getBytes(StandardCharsets.US_ASCII),
    "WARNING:".getBytes(StandardCharsets.US_ASCII),
    "level=warn".getBytes(StandardCharsets.US_ASCII)
  ),
  infoKeywords = Array(
    " INFO ".getBytes(StandardCharsets.US_ASCII),
    "[INFO]".getBytes(StandardCharsets.US_ASCII),
    "INFO:".getBytes(StandardCharsets.US_ASCII),
    "level=info".getBytes(StandardCharsets.US_ASCII)
  ),
  fallbackErrorKeywords = Array(
    "Exception".getBytes(StandardCharsets.US_ASCII),
    "Throwable".getBytes(StandardCharsets.US_ASCII),
    "Caused by:".getBytes(StandardCharsets.US_ASCII)
  )
) {
  private val CausedByBytes = "Caused by:".getBytes(StandardCharsets.US_ASCII)
  private val SuppressedBytes = "Suppressed:".getBytes(StandardCharsets.US_ASCII)

  def isEventHeaderPrefix(segment: MemorySegment, offset: Long, availableLength: Long): Boolean = {
    if (segment == MemorySegment.NULL || availableLength <= 0L) {
      false
    } else {
      val firstByte: Byte = segment.get(ValueLayout.JAVA_BYTE, offset)
      if (firstByte == '\n'.toByte || firstByte == '\r'.toByte || firstByte == '\t'.toByte) {
        false
      } else if (firstByte == ' '.toByte && availableLength > 1L && segment.get(ValueLayout.JAVA_BYTE, offset + 1L) == ' '.toByte) {
        false
      } else if (firstByte == 'a'.toByte && availableLength >= 3L &&
                 segment.get(ValueLayout.JAVA_BYTE, offset + 1L) == 't'.toByte &&
                 segment.get(ValueLayout.JAVA_BYTE, offset + 2L) == ' '.toByte) {
        false
      } else if (firstByte == '.'.toByte && availableLength >= 4L &&
                 segment.get(ValueLayout.JAVA_BYTE, offset + 1L) == '.'.toByte &&
                 segment.get(ValueLayout.JAVA_BYTE, offset + 2L) == '.'.toByte &&
                 segment.get(ValueLayout.JAVA_BYTE, offset + 3L) == ' '.toByte) {
        false
      } else if (firstByte == 'C'.toByte && availableLength >= CausedByBytes.length.toLong && matchesBytesAt(segment, offset, CausedByBytes)) {
        false
      } else if (firstByte == 'S'.toByte && availableLength >= SuppressedBytes.length.toLong && matchesBytesAt(segment, offset, SuppressedBytes)) {
        false
      } else {
        true
      }
    }
  }
}

/**
 * Python Profile (logging, traceback, django, fastapi).
 */
object PythonProfile extends BaseLogLanguageProfile(
  id = "python",
  displayName = "Python (traceback, logging)",
  errorKeywords = Array(
    " ERROR ".getBytes(StandardCharsets.US_ASCII),
    "[ERROR]".getBytes(StandardCharsets.US_ASCII),
    "ERROR:".getBytes(StandardCharsets.US_ASCII),
    " CRITICAL ".getBytes(StandardCharsets.US_ASCII),
    "[CRITICAL]".getBytes(StandardCharsets.US_ASCII),
    "level=error".getBytes(StandardCharsets.US_ASCII)
  ),
  warnKeywords = Array(
    " WARNING ".getBytes(StandardCharsets.US_ASCII),
    "[WARNING]".getBytes(StandardCharsets.US_ASCII),
    "WARNING:".getBytes(StandardCharsets.US_ASCII),
    " WARN ".getBytes(StandardCharsets.US_ASCII),
    "[WARN]".getBytes(StandardCharsets.US_ASCII),
    "level=warn".getBytes(StandardCharsets.US_ASCII)
  ),
  infoKeywords = Array(
    " INFO ".getBytes(StandardCharsets.US_ASCII),
    "[INFO]".getBytes(StandardCharsets.US_ASCII),
    "INFO:".getBytes(StandardCharsets.US_ASCII),
    "level=info".getBytes(StandardCharsets.US_ASCII)
  ),
  fallbackErrorKeywords = Array(
    "Traceback (most recent call last):".getBytes(StandardCharsets.US_ASCII),
    "Exception".getBytes(StandardCharsets.US_ASCII),
    "Error:".getBytes(StandardCharsets.US_ASCII)
  )
) {
  private val TracebackBytes = "Traceback (most recent call last):".getBytes(StandardCharsets.US_ASCII)
  private val FileBytes = "  File \"".getBytes(StandardCharsets.US_ASCII)

  def isEventHeaderPrefix(segment: MemorySegment, offset: Long, availableLength: Long): Boolean = {
    if (segment == MemorySegment.NULL || availableLength <= 0L) {
      false
    } else {
      val firstByte: Byte = segment.get(ValueLayout.JAVA_BYTE, offset)
      if (firstByte == '\n'.toByte || firstByte == '\r'.toByte || firstByte == '\t'.toByte) {
        false
      } else if (firstByte == ' '.toByte && availableLength >= FileBytes.length.toLong && matchesBytesAt(segment, offset, FileBytes)) {
        false
      } else if (firstByte == ' '.toByte && availableLength > 3L && segment.get(ValueLayout.JAVA_BYTE, offset + 1L) == ' '.toByte) {
        false
      } else {
        true
      }
    }
  }
}

/**
 * Go Profile (zap, logrus, slog, panic).
 */
object GoProfile extends BaseLogLanguageProfile(
  id = "go",
  displayName = "Go (slog, zap, panic)",
  errorKeywords = Array(
    "\"level\":\"error\"".getBytes(StandardCharsets.US_ASCII),
    "level=error".getBytes(StandardCharsets.US_ASCII),
    "\"level\":\"fatal\"".getBytes(StandardCharsets.US_ASCII),
    "level=fatal".getBytes(StandardCharsets.US_ASCII),
    " ERROR ".getBytes(StandardCharsets.US_ASCII),
    "[ERROR]".getBytes(StandardCharsets.US_ASCII)
  ),
  warnKeywords = Array(
    "\"level\":\"warn\"".getBytes(StandardCharsets.US_ASCII),
    "level=warn".getBytes(StandardCharsets.US_ASCII),
    "\"level\":\"warning\"".getBytes(StandardCharsets.US_ASCII),
    "level=warning".getBytes(StandardCharsets.US_ASCII),
    " WARN ".getBytes(StandardCharsets.US_ASCII),
    "[WARN]".getBytes(StandardCharsets.US_ASCII)
  ),
  infoKeywords = Array(
    "\"level\":\"info\"".getBytes(StandardCharsets.US_ASCII),
    "level=info".getBytes(StandardCharsets.US_ASCII),
    " INFO ".getBytes(StandardCharsets.US_ASCII),
    "[INFO]".getBytes(StandardCharsets.US_ASCII)
  ),
  fallbackErrorKeywords = Array(
    "panic:".getBytes(StandardCharsets.US_ASCII),
    "goroutine ".getBytes(StandardCharsets.US_ASCII),
    "runtime error:".getBytes(StandardCharsets.US_ASCII)
  )
) {
  private val GoroutineBytes = "goroutine ".getBytes(StandardCharsets.US_ASCII)

  def isEventHeaderPrefix(segment: MemorySegment, offset: Long, availableLength: Long): Boolean = {
    if (segment == MemorySegment.NULL || availableLength <= 0L) {
      false
    } else {
      val firstByte: Byte = segment.get(ValueLayout.JAVA_BYTE, offset)
      if (firstByte == '\n'.toByte || firstByte == '\r'.toByte || firstByte == '\t'.toByte) {
        false
      } else if (firstByte == 'g'.toByte && availableLength >= GoroutineBytes.length.toLong && matchesBytesAt(segment, offset, GoroutineBytes)) {
        false
      } else if (firstByte == ' '.toByte && availableLength > 1L && segment.get(ValueLayout.JAVA_BYTE, offset + 1L) == ' '.toByte) {
        false
      } else {
        true
      }
    }
  }
}

/**
 * Rust Profile (env_logger, tracing, panic).
 */
object RustProfile extends BaseLogLanguageProfile(
  id = "rust",
  displayName = "Rust (tracing, env_logger)",
  errorKeywords = Array(
    " ERROR ".getBytes(StandardCharsets.US_ASCII),
    "[ERROR]".getBytes(StandardCharsets.US_ASCII),
    "level = \"ERROR\"".getBytes(StandardCharsets.US_ASCII),
    "level = \"error\"".getBytes(StandardCharsets.US_ASCII)
  ),
  warnKeywords = Array(
    " WARN ".getBytes(StandardCharsets.US_ASCII),
    "[WARN]".getBytes(StandardCharsets.US_ASCII),
    "level = \"WARN\"".getBytes(StandardCharsets.US_ASCII),
    "level = \"warn\"".getBytes(StandardCharsets.US_ASCII)
  ),
  infoKeywords = Array(
    " INFO ".getBytes(StandardCharsets.US_ASCII),
    "[INFO]".getBytes(StandardCharsets.US_ASCII),
    "level = \"INFO\"".getBytes(StandardCharsets.US_ASCII),
    "level = \"info\"".getBytes(StandardCharsets.US_ASCII)
  ),
  fallbackErrorKeywords = Array(
    "panicked at".getBytes(StandardCharsets.US_ASCII),
    "stack backtrace:".getBytes(StandardCharsets.US_ASCII)
  )
) {
  private val StackBacktraceBytes = "stack backtrace:".getBytes(StandardCharsets.US_ASCII)

  def isEventHeaderPrefix(segment: MemorySegment, offset: Long, availableLength: Long): Boolean = {
    if (segment == MemorySegment.NULL || availableLength <= 0L) {
      false
    } else {
      val firstByte: Byte = segment.get(ValueLayout.JAVA_BYTE, offset)
      if (firstByte == '\n'.toByte || firstByte == '\r'.toByte || firstByte == '\t'.toByte) {
        false
      } else if (firstByte == 's'.toByte && availableLength >= StackBacktraceBytes.length.toLong && matchesBytesAt(segment, offset, StackBacktraceBytes)) {
        false
      } else if (firstByte == ' '.toByte && availableLength > 1L && segment.get(ValueLayout.JAVA_BYTE, offset + 1L) == ' '.toByte) {
        false
      } else {
        true
      }
    }
  }
}

/**
 * Generic Syslog Profile (RFC 5424, systemd, journald).
 */
object SyslogProfile extends BaseLogLanguageProfile(
  id = "syslog",
  displayName = "Syslog & Generic",
  errorKeywords = Array(
    "<3>".getBytes(StandardCharsets.US_ASCII),
    "<2>".getBytes(StandardCharsets.US_ASCII),
    "<1>".getBytes(StandardCharsets.US_ASCII),
    "<0>".getBytes(StandardCharsets.US_ASCII),
    " ERROR ".getBytes(StandardCharsets.US_ASCII),
    "[ERROR]".getBytes(StandardCharsets.US_ASCII),
    " err ".getBytes(StandardCharsets.US_ASCII),
    " crit ".getBytes(StandardCharsets.US_ASCII)
  ),
  warnKeywords = Array(
    "<4>".getBytes(StandardCharsets.US_ASCII),
    " WARN ".getBytes(StandardCharsets.US_ASCII),
    "[WARN]".getBytes(StandardCharsets.US_ASCII),
    " warning ".getBytes(StandardCharsets.US_ASCII)
  ),
  infoKeywords = Array(
    "<6>".getBytes(StandardCharsets.US_ASCII),
    "<5>".getBytes(StandardCharsets.US_ASCII),
    " INFO ".getBytes(StandardCharsets.US_ASCII),
    "[INFO]".getBytes(StandardCharsets.US_ASCII),
    " notice ".getBytes(StandardCharsets.US_ASCII)
  ),
  fallbackErrorKeywords = Array(
    "failure".getBytes(StandardCharsets.US_ASCII),
    "fatal".getBytes(StandardCharsets.US_ASCII)
  )
) {
  def isEventHeaderPrefix(segment: MemorySegment, offset: Long, availableLength: Long): Boolean = {
    if (segment == MemorySegment.NULL || availableLength <= 0L) {
      false
    } else {
      val firstByte: Byte = segment.get(ValueLayout.JAVA_BYTE, offset)
      firstByte != '\n'.toByte && firstByte != '\r'.toByte && firstByte != '\t'.toByte
    }
  }
}

/**
 * Registry and auto-detection engine for log language profiles.
 */
object LanguageProfileRegistry {

  val AllProfiles: List[LogLanguageProfile] = List(
    JavaJvmProfile,
    PythonProfile,
    GoProfile,
    RustProfile,
    SyslogProfile
  )

  private val ProfilesById: Map[String, LogLanguageProfile] =
    AllProfiles.map(profile => profile.id -> profile).toMap

  def forId(profileId: String): LogLanguageProfile = {
    ProfilesById.getOrElse(profileId.toLowerCase, JavaJvmProfile)
  }

  /**
   * Fast auto-detection inspecting up to the first 512KB of a log file.
   */
  def autoDetect(storageReader: LogStorageReader): LogLanguageProfile = {
    if (storageReader == null || storageReader.sizeBytes <= 0L) {
      JavaJvmProfile
    } else {
      val sampleSize = math.min(storageReader.sizeBytes, 512L * 1024L)
      try {
        val sampleSlice = storageReader.readSlice(0L, sampleSize)
        val sampleBytes = new Array[Byte](sampleSize.toInt)
        MemorySegment.copy(sampleSlice, ValueLayout.JAVA_BYTE, 0L, sampleBytes, 0, sampleSize.toInt)
        val sampleText = new String(sampleBytes, StandardCharsets.ISO_8859_1)

        if (sampleText.contains("Traceback (most recent call last):") || sampleText.contains(".py\"")) {
          PythonProfile
        } else if (sampleText.contains("goroutine ") || sampleText.contains("level=error") || sampleText.contains(".go:")) {
          GoProfile
        } else if (sampleText.contains("panicked at") || sampleText.contains(".rs:")) {
          RustProfile
        } else if (sampleText.contains("at com.") || sampleText.contains("at org.") || sampleText.contains("Exception in thread")) {
          JavaJvmProfile
        } else {
          // Default to Java/JVM which is most comprehensive
          JavaJvmProfile
        }
      } catch {
        case _: Throwable => JavaJvmProfile
      }
    }
  }
}
