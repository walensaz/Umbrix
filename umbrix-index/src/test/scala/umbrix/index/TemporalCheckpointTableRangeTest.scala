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
    } finally {
      arena.close()
    }
  }
}
