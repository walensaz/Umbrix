package umbrix.ui.timeline

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IncidentReportExporterTest {

    @Test
    fun testGenerateMarkdownReport() {
        val events = listOf(
            TimelineEvent(
                sourceDocumentPath = "/var/log/service-a.log",
                sourceDocumentTitle = "service-a.log",
                lineIndex = 1420L,
                timestampMicros = 1727366400000000L, // 2024-09-26 16:00:00.000
                lineContent = "[WARN] High memory pressure detected",
                tag = EventTag.TRIGGER,
                userNote = "Initial alert fired by Prometheus"
            ),
            TimelineEvent(
                sourceDocumentPath = "/var/log/service-b.log",
                sourceDocumentTitle = "service-b.log",
                lineIndex = 512L,
                timestampMicros = 1727366412400000L, // +12.4s later
                lineContent = "[ERROR] OutOfMemoryError: Java heap space",
                tag = EventTag.ROOT_CAUSE,
                userNote = "Fatal OOM in worker thread"
            )
        )

        val markdown = IncidentReportExporter.generateMarkdownReport(events, "Production Incident P1")
        assertTrue(markdown.contains("# Production Incident P1"))
        assertTrue(markdown.contains("Total Events Analyzed: **2**"))
        assertTrue(markdown.contains("`service-a.log`"))
        assertTrue(markdown.contains("`service-b.log`"))
        assertTrue(markdown.contains("**Trigger**"))
        assertTrue(markdown.contains("**Root Cause**"))
        assertTrue(markdown.contains("`+12.4s`"))
        assertTrue(markdown.contains("Initial alert fired by Prometheus"))
        assertTrue(markdown.contains("[ERROR] OutOfMemoryError: Java heap space"))
    }

    @Test
    fun testGenerateConsolidatedLog() {
        val events = listOf(
            TimelineEvent(
                sourceDocumentPath = "/var/log/app.log",
                sourceDocumentTitle = "app.log",
                lineIndex = 10L,
                timestampMicros = 1000000L,
                lineContent = "Starting application...",
                tag = EventTag.EVIDENCE,
                userNote = "Boot sequence"
            )
        )

        val log = IncidentReportExporter.generateConsolidatedLog(events)
        assertTrue(log.contains("# --- [app.log:11]"))
        assertTrue(log.contains("# Note: Boot sequence"))
        assertTrue(log.contains("Starting application..."))
    }

    @Test
    fun testGenerateJsonArchive() {
        val events = listOf(
            TimelineEvent(
                id = "evt-123",
                sourceDocumentPath = "/var/log/app.log",
                sourceDocumentTitle = "app.log",
                lineIndex = 10L,
                timestampMicros = 1000000L,
                lineContent = "Line with \"quotes\" and \n newline",
                tag = EventTag.ANOMALY,
                userNote = "Check quotes"
            )
        )

        val json = IncidentReportExporter.generateJsonArchive(events)
        assertTrue(json.contains("\"id\": \"evt-123\""))
        assertTrue(json.contains("\"sourceTitle\": \"app.log\""))
        assertTrue(json.contains("\\\"quotes\\\""))
        assertTrue(json.contains("\\n newline"))
    }

    @Test
    fun testFormatDelta() {
        assertEquals("+0ms", TimelineFormatters.formatDelta(0L))
        assertEquals("+500μs", TimelineFormatters.formatDelta(500L))
        assertEquals("+250ms", TimelineFormatters.formatDelta(250_000L))
        assertEquals("+3.2s", TimelineFormatters.formatDelta(3_200_000L))
        assertEquals("+1m 30s", TimelineFormatters.formatDelta(90_000_000L))
        assertEquals("-500ms", TimelineFormatters.formatDelta(-500_000L))
    }
}
