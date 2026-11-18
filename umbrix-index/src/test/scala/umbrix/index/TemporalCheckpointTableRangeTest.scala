package umbrix.index

import it.unimi.dsi.fastutil.longs.LongArrayList
import munit.FunSuite
import umbrix.core.types.{EpochMicros, LineIndex}

class TemporalCheckpointTableRangeTest extends FunSuite {

  test("empty table returns safe defaults for all range operations") {
    val table: TemporalCheckpointTable = TemporalCheckpointTable.empty
    assertEquals(table.checkpointCount, 0)
    assertEquals(table.firstTimestamp, None)
    assertEquals(table.lastTimestamp, None)
    assertEquals(table.seekLineRange(EpochMicros(1000L), EpochMicros(2000L)), (LineIndex.Zero, LineIndex.Zero))
    assertEquals(table.closestCheckpointIndexForLine(LineIndex(500L)), 0)
  }

  test("table with checkpoints correctly resolves bounds, line ranges, and checkpoint indices") {
    val lines: LongArrayList = new LongArrayList()
    val micros: LongArrayList = new LongArrayList()

    // 4 checkpoints:
    // Line 0:     1,000,000 us (1.0s)
    // Line 8192:  2,000,000 us (2.0s)
    // Line 16384: 3,000,000 us (3.0s)
    // Line 24576: 4,000,000 us (4.0s)
    lines.add(0L)
    micros.add(1000000L)

    lines.add(8192L)
    micros.add(2000000L)

    lines.add(16384L)
    micros.add(3000000L)

    lines.add(24576L)
    micros.add(4000000L)

    val table: TemporalCheckpointTable = new TemporalCheckpointTable(lines, micros)

    assertEquals(table.checkpointCount, 4)
    assertEquals(table.firstTimestamp, Some(EpochMicros(1000000L)))
    assertEquals(table.lastTimestamp, Some(EpochMicros(4000000L)))

    assertEquals(table.timestampAtCheckpoint(1), EpochMicros(2000000L))
    assertEquals(table.lineAtCheckpoint(2), LineIndex(16384L))

    // Exact matches
    val (startExact, endExact) = table.seekLineRange(EpochMicros(1000000L), EpochMicros(3000000L))
    assertEquals(startExact, LineIndex(0L))
    assertEquals(endExact, LineIndex(16384L))

    // Intermediate range queries (upper bound encloses through next checkpoint)
    val (startMid, endMid) = table.seekLineRange(EpochMicros(1500000L), EpochMicros(3500000L))
    assertEquals(startMid, LineIndex(0L))
    assertEquals(endMid, LineIndex(24576L))

    // Inverted bounds auto-normalize
    val (startInverted, endInverted) = table.seekLineRange(EpochMicros(3000000L), EpochMicros(1000000L))
    assertEquals(startInverted, LineIndex(0L))
    assertEquals(endInverted, LineIndex(16384L))

    // Closest checkpoint for line lookup
    assertEquals(table.closestCheckpointIndexForLine(LineIndex(0L)), 0)
    assertEquals(table.closestCheckpointIndexForLine(LineIndex(4000L)), 0)
    assertEquals(table.closestCheckpointIndexForLine(LineIndex(7000L)), 1)
    assertEquals(table.closestCheckpointIndexForLine(LineIndex(8192L)), 1)
    assertEquals(table.closestCheckpointIndexForLine(LineIndex(20000L)), 2)
    assertEquals(table.closestCheckpointIndexForLine(LineIndex(30000L)), 3)
  }

  test("non-monotonic out-of-order logs resolve ranges and nearest lines robustly without collapsing") {
    val lines: LongArrayList = new LongArrayList()
    val micros: LongArrayList = new LongArrayList()

    // Non-monotonic log structure (like concatenated Hadoop logs):
    // Line 0:      Oct 22 (1477142893000L)
    // Line 8192:   June 08 (1496918664000L)
    // Line 16384:  Oct 03 (1475528762000L) -- jumps backwards!
    // Line 24576:  July 31 (1501525416000L)
    lines.add(0L)
    micros.add(1477142893000L)

    lines.add(8192L)
    micros.add(1496918664000L)

    lines.add(16384L)
    micros.add(1475528762000L)

    lines.add(24576L)
    micros.add(1501525416000L)

    val table: TemporalCheckpointTable = new TemporalCheckpointTable(lines, micros, LineIndex(30000L))

    // True min and max are correctly identified across all checkpoints
    assertEquals(table.firstTimestamp, Some(EpochMicros(1475528762000L)))
    assertEquals(table.lastTimestamp, Some(EpochMicros(1501525416000L)))

    // Full range [min, max] spans entire file from 0 to totalLines - 1
    val (fullStart, fullEnd) = table.seekLineRange(EpochMicros(1475528762000L), EpochMicros(1501525416000L))
    assertEquals(fullStart, LineIndex(0L))
    assertEquals(fullEnd, LineIndex(29999L))

    // Half range [min, mid] covers lines containing earlier events without collapsing
    val midT = EpochMicros(1475528762000L + (1501525416000L - 1475528762000L) / 2)
    val (halfStart, halfEnd) = table.seekLineRange(EpochMicros(1475528762000L), midT)
    assertEquals(halfStart, LineIndex(0L))
    assertEquals(halfEnd, LineIndex(16384L))

    // Nearest line for Oct 03 targets Line 16384 correctly
    val nearestOct03 = table.seekNearestLine(EpochMicros(1475528762000L))
    assertEquals(nearestOct03, LineIndex(16384L))

    // Nearest line for July 31 targets Line 24576 correctly
    val nearestJuly31 = table.seekNearestLine(EpochMicros(1501525416000L))
    assertEquals(nearestJuly31, LineIndex(24576L))
  }

  test("tryParseTimestamp correctly parses Thunderbird log lines with epoch prefix") {
    val line = "- 1131523501 2005.11.09 aadmin1 Nov 10 00:05:01 src@aadmin1 in.tftpd[14620]: tftp: client does not accept options\n"
    val arena = java.lang.foreign.Arena.ofConfined()
    try {
      val segment = arena.allocateFrom(line)
      val parsed = TemporalCheckpointTable.tryParseTimestamp(segment)
      assert(parsed.isDefined, "Should successfully parse timestamp from Thunderbird log format")
      assertEquals(parsed.get, 1131523501L * 1000000L)
    } finally {
      arena.close()
    }
  }

  test("tryParseTimestamp correctly parses ISO-8601, Dotted, Syslog, and Apache CLF formats") {
    val arena = java.lang.foreign.Arena.ofConfined()
    try {
      // ISO-8601 with millis
      val isoLine = "2026-09-22 10:14:00.123 [INFO] Application started"
      val isoParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(isoLine))
      assert(isoParsed.isDefined)
      val isoDt = java.time.LocalDateTime.of(2026, 9, 22, 10, 14, 0).toEpochSecond(java.time.ZoneOffset.UTC) * 1000000L + 123000L
      assertEquals(isoParsed.get, isoDt)

      // Dotted date-time
      val dottedLine = "2005.11.09-00:05:01 [main] server ready"
      val dottedParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(dottedLine))
      assert(dottedParsed.isDefined)

      // Syslog RFC 3164 with year in line
      val syslogLine = "Nov 10 00:05:01 myhost in.tftpd[14620]: client connected"
      val syslogParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(syslogLine))
      assert(syslogParsed.isDefined)

      // Apache Common Log Format
      val clfLine = """127.0.0.1 - - [10/Nov/2005:00:05:01 +0000] "GET /index.html HTTP/1.1" 200 1234"""
      val clfParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(clfLine))
      assert(clfParsed.isDefined)

      // Epoch millis
      val epochMillisLine = "[1609459200000] Message logged"
      val epochMillisParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(epochMillisLine))
      assert(epochMillisParsed.isDefined)
      assertEquals(epochMillisParsed.get, 1609459200000L * 1000L)

      // Time-of-day with millis (single-day logs)
      val timeOfDayLine = "14:23:01.123 [INFO] Worker pool initialized"
      val timeParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(timeOfDayLine))
      assert(timeParsed.isDefined)
      val refDt = java.time.LocalDateTime.of(2026, 1, 1, 14, 23, 1).toEpochSecond(java.time.ZoneOffset.UTC) * 1000000L + 123000L
      assertEquals(timeParsed.get, refDt)

      // Bracketed Time-of-day
      val bracketedTime = "[09:15:30] Service started"
      val bracketedParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(bracketedTime))
      assert(bracketedParsed.isDefined)

      // Month-day with time (Logcat / Tomcat)
      val logcatLine = "10-09 14:23:01.456 1234 5678 D MyApp: Debug message"
      val logcatParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(logcatLine))
      assert(logcatParsed.isDefined)

      // 2-digit year (yy-MM-dd)
      val twoDigitYearLine = "24-10-09 14:23:01 [main] server started"
      val twoDigitParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(twoDigitYearLine))
      assert(twoDigitParsed.isDefined)
      val expected24 = java.time.LocalDateTime.of(2024, 10, 9, 14, 23, 1).toEpochSecond(java.time.ZoneOffset.UTC) * 1000000L
      assertEquals(twoDigitParsed.get, expected24)

      // US Date (MM/dd/yyyy)
      val usDateLine = "10/09/2026 14:23:01.500 Transaction processed"
      val usParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(usDateLine))
      assert(usParsed.isDefined)
    } finally {
      arena.close()
    }
  }

  test("single-day time-of-day 00:00:00.000 through 24:00:00.00 parses accurately with variable fractional digits") {
    val arena = java.lang.foreign.Arena.ofConfined()
    try {
      val baseMicros = TemporalCheckpointTable.ReferenceDateEpochMicros

      val startLine = "00:00:00.000 [INFO] Daily log started"
      val startParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(startLine))
      assertEquals(startParsed, Some(baseMicros))
      assertEquals(TemporalCheckpointTable.detectLineFormat(arena.allocateFrom(startLine)), TemporalCheckpointTable.FormatTimeOfDay)

      // 2-digit fractional seconds (.78 = 780,000 micros)
      val centiLine = "12:34:56.78 [INFO] Midday checkpoint"
      val centiParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(centiLine))
      val expectedCenti = baseMicros + ((12L * 3600L + 34L * 60L + 56L) * 1000000L) + 780000L
      assertEquals(centiParsed, Some(expectedCenti))

      // 1-digit fractional seconds (.9 = 900,000 micros)
      val deciLine = "23:59:59.9 [WARN] Almost midnight"
      val deciParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(deciLine))
      val expectedDeci = baseMicros + ((23L * 3600L + 59L * 60L + 59L) * 1000000L) + 900000L
      assertEquals(deciParsed, Some(expectedDeci))

      // End-of-day 24:00:00.00 (exact 86,400 seconds span)
      val endLine = "24:00:00.00 [INFO] Daily rotation complete"
      val endParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(endLine))
      assertEquals(endParsed, Some(baseMicros + 86400000000L))
      assertEquals(endParsed.get - startParsed.get, 86400000000L)
    } finally {
      arena.close()
    }
  }

  test("single-day format auto-detection prevents mid-line false-positive multi-day dates") {
    val arena = java.lang.foreign.Arena.ofConfined()
    try {
      val headerLog =
        "00:00:00.000 [INFO] Service started\n" +
        "00:01:15.25 [INFO] Connected host 10.20.01.05\n" +
        "00:02:30.50 [ERROR] worker-01-05 failed with count=1700000000\n"
      val headerSeg = arena.allocateFrom(headerLog)
      val detectedFormat = TemporalCheckpointTable.detectDominantFormatFromSegment(headerSeg)
      assertEquals(detectedFormat, TemporalCheckpointTable.FormatTimeOfDay)

      // Continuation / payload lines that used to trigger false positives must return None under FormatTimeOfDay and FormatUnknown
      val stackTraceEpochFalsePositive = "    at com.example.Service.process(Service.java:123) count=1700000000"
      assertEquals(TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(stackTraceEpochFalsePositive)), None)

      val macAddressLine = "Device MAC 00:11:22:33:44:55 connected"
      assertEquals(TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(macAddressLine)), None)

      val lowercaseMayLine = "Task may 10 12:00:00 retry scheduled"
      assertEquals(TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(lowercaseMayLine), TemporalCheckpointTable.FormatSyslog), None)

      val workerHyphenPrefix = "worker-01-05 12:30:00.000 [INFO] Processing batch"
      val workerParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(workerHyphenPrefix), TemporalCheckpointTable.FormatTimeOfDay)
      val expectedWorker = TemporalCheckpointTable.ReferenceDateEpochMicros + ((12L * 3600L + 30L * 60L) * 1000000L)
      assertEquals(workerParsed, Some(expectedWorker))

      // Line-bounded slicing must not cross newline into next line
      val multiLineSlice = "no timestamp here\n2028-05-10 10:00:00.000 next line"
      assertEquals(TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(multiLineSlice)), None)
    } finally {
      arena.close()
    }
  }

  test("broad format auto-detection covers compact dates, glog, Tomcat, European dates, and epoch nanos") {
    val arena = java.lang.foreign.Arena.ofConfined()
    try {
      // Compact YYYYMMDD HH:mm:ss.SSS
      val compactLine = "20261112 14:23:01.123 [INFO] Compact date"
      val compactParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(compactLine))
      val expectedCompact = java.time.LocalDateTime.of(2026, 11, 12, 14, 23, 1).toEpochSecond(java.time.ZoneOffset.UTC) * 1000000L + 123000L
      assertEquals(compactParsed, Some(expectedCompact))

      // Google glog/klog I1112 14:23:01.123456
      val glogLine = "I1112 14:23:01.123456 9876 server.cc:42] Ready"
      val glogParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(glogLine))
      val expectedGlog = java.time.LocalDateTime.of(2026, 11, 12, 14, 23, 1).toEpochSecond(java.time.ZoneOffset.UTC) * 1000000L + 123456L
      assertEquals(glogParsed, Some(expectedGlog))

      // Tomcat dd-MMM-yyyy HH:mm:ss.SSS
      val tomcatLine = "12-Nov-2026 14:23:01.456 INFO [main] org.apache.catalina.startup.Catalina.start"
      val tomcatParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(tomcatLine))
      val expectedTomcat = java.time.LocalDateTime.of(2026, 11, 12, 14, 23, 1).toEpochSecond(java.time.ZoneOffset.UTC) * 1000000L + 456000L
      assertEquals(tomcatParsed, Some(expectedTomcat))

      // European dd/MM/yyyy HH:mm:ss.SS
      val eurLine = "22/09/2026 10:14:00.50 [INFO] European format"
      val eurParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(eurLine))
      val expectedEur = java.time.LocalDateTime.of(2026, 9, 22, 10, 14, 0).toEpochSecond(java.time.ZoneOffset.UTC) * 1000000L + 500000L
      assertEquals(eurParsed, Some(expectedEur))

      // JSON explicit key with epoch nanoseconds (19 digits)
      val jsonNanoLine = """{"timestamp": 1700000000123456789, "msg": "ok"}"""
      val jsonNanoParsed = TemporalCheckpointTable.tryParseTimestamp(arena.allocateFrom(jsonNanoLine))
      assertEquals(jsonNanoParsed, Some(1700000000123456L))
    } finally {
      arena.close()
    }
  }
}
