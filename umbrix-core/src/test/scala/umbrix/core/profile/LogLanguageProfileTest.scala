package umbrix.core.profile

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.charset.StandardCharsets
import munit.FunSuite

class LogLanguageProfileTest extends FunSuite {

  private def toSegment(text: String, arena: Arena): MemorySegment = {
    val bytes = text.getBytes(StandardCharsets.UTF_8)
    val seg = arena.allocate(bytes.length.toLong, 1)
    MemorySegment.copy(MemorySegment.ofArray(bytes), 0L, seg, 0L, bytes.length.toLong)
    seg
  }

  test("JavaJvmProfile correctly classifies line severities and continuation frames") {
    val arena = Arena.ofConfined()
    try {
      val errorLine = toSegment("2026-09-23 12:00:00 [main] ERROR com.app.Service - DB connection failed", arena)
      val warnLine = toSegment("2026-09-23 12:00:00 [main] WARN com.app.Service - Slow response 1200ms", arena)
      val infoLine = toSegment("2026-09-23 12:00:00 [main] INFO com.app.Service - Server started on port 8080", arena)
      val normalLine = toSegment("2026-09-23 12:00:00 [main] DEBUG com.app.Service - Cache hit key=42", arena)

      assertEquals(JavaJvmProfile.classifyLineSeverity(errorLine, 0L, errorLine.byteSize()), LineSeverity.Error)
      assertEquals(JavaJvmProfile.classifyLineSeverity(warnLine, 0L, warnLine.byteSize()), LineSeverity.Warn)
      assertEquals(JavaJvmProfile.classifyLineSeverity(infoLine, 0L, infoLine.byteSize()), LineSeverity.Info)
      assertEquals(JavaJvmProfile.classifyLineSeverity(normalLine, 0L, normalLine.byteSize()), LineSeverity.Normal)

      // INFO line mentioning an exception in payload remains Info, not Error (Hadoop log test case)
      val infoWithExceptionInPayload = toSegment("2016-10-27 12:32:55,820 INFO org.apache.hadoop.ipc.Server: call getBlockLocations: java.io.FileNotFoundException: File does not exist", arena)
      assertEquals(JavaJvmProfile.classifyLineSeverity(infoWithExceptionInPayload, 0L, infoWithExceptionInPayload.byteSize()), LineSeverity.Info)

      // Raw exception without level header falls back to Error
      val rawExceptionLine = toSegment("java.nio.channels.ClosedChannelException", arena)
      assertEquals(JavaJvmProfile.classifyLineSeverity(rawExceptionLine, 0L, rawExceptionLine.byteSize()), LineSeverity.Error)

      // Event headers vs stack frames
      val stackFrame = toSegment("    at com.app.Service.process(Service.java:42)", arena)
      val causedByFrame = toSegment("Caused by: java.sql.SQLException: connection reset", arena)
      val headerLine = toSegment("2026-09-23 12:00:00 [ERROR] Exception in thread main", arena)

      assert(!JavaJvmProfile.isEventHeaderPrefix(stackFrame, 0L, stackFrame.byteSize()))
      assert(!JavaJvmProfile.isEventHeaderPrefix(causedByFrame, 0L, causedByFrame.byteSize()))
      assert(JavaJvmProfile.isEventHeaderPrefix(headerLine, 0L, headerLine.byteSize()))
    } finally {
      arena.close()
    }
  }

  test("PythonProfile correctly classifies tracebacks and exceptions") {
    val arena = Arena.ofConfined()
    try {
      val errorLine = toSegment("2026-09-23 12:00:00 [ERROR] ZeroDivisionError: division by zero", arena)
      val tracebackHeader = toSegment("Traceback (most recent call last):", arena)
      val tracebackFrame = toSegment("  File \"server.py\", line 120, in handle_request", arena)

      assertEquals(PythonProfile.classifyLineSeverity(errorLine, 0L, errorLine.byteSize()), LineSeverity.Error)
      assert(PythonProfile.isEventHeaderPrefix(tracebackHeader, 0L, tracebackHeader.byteSize()))
      assert(!PythonProfile.isEventHeaderPrefix(tracebackFrame, 0L, tracebackFrame.byteSize()))
    } finally {
      arena.close()
    }
  }

  test("GoProfile correctly classifies panic and goroutine traces") {
    val arena = Arena.ofConfined()
    try {
      val panicLine = toSegment("panic: runtime error: invalid memory address", arena)
      val goroutineFrame = toSegment("goroutine 1 [running]:", arena)
      val normalHeader = toSegment("2026-09-23T12:00:00Z level=info msg=\"request completed\"", arena)

      assertEquals(GoProfile.classifyLineSeverity(panicLine, 0L, panicLine.byteSize()), LineSeverity.Error)
      assert(!GoProfile.isEventHeaderPrefix(goroutineFrame, 0L, goroutineFrame.byteSize()))
      assert(GoProfile.isEventHeaderPrefix(normalHeader, 0L, normalHeader.byteSize()))
    } finally {
      arena.close()
    }
  }

  test("LanguageProfileRegistry resolves known profiles and falls back cleanly") {
    assertEquals(LanguageProfileRegistry.forId("java").id, "java")
    assertEquals(LanguageProfileRegistry.forId("python").id, "python")
    assertEquals(LanguageProfileRegistry.forId("go").id, "go")
    assertEquals(LanguageProfileRegistry.forId("rust").id, "rust")
    assertEquals(LanguageProfileRegistry.forId("syslog").id, "syslog")
    assertEquals(LanguageProfileRegistry.forId("unknown").id, "java")
  }
}
