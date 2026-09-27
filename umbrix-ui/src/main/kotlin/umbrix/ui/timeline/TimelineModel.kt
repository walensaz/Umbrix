package umbrix.ui.timeline

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Causal event classifications for incident reconstruction.
 */
enum class EventTag(val displayName: String, val colorHex: Long) {
    TRIGGER("Trigger", 0xFFE5C07B),           // Amber
    ROOT_CAUSE("Root Cause", 0xFFE06C75),     // Coral Red
    CASCADE("Cascade", 0xFFD19A66),           // Orange
    ANOMALY("Anomaly", 0xFF61AFEF),           // Light Blue
    EVIDENCE("Evidence", 0xFF98C379),         // Green
    RESOLVED("Resolved", 0xFF56B6C2);         // Cyan

    companion object {
        fun fromString(value: String): EventTag {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.displayName.equals(value, ignoreCase = true) }
                ?: ANOMALY
        }
    }
}

/**
 * An event captured from a log file into the central Investigation Timeline.
 */
data class TimelineEvent(
    val id: String = UUID.randomUUID().toString(),
    val sourceDocumentPath: String,
    val sourceDocumentTitle: String,
    val lineIndex: Long,
    val timestampMicros: Long = 0L,
    val lineContent: String,
    val tag: EventTag = EventTag.ANOMALY,
    val userNote: String = "",
    val createdAtMs: Long = System.currentTimeMillis()
)

object TimelineFormatters {
    private val ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    private val TIME_ONLY_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    fun formatTimestamp(micros: Long, includeDate: Boolean = false): String {
        if (micros <= 0L) return "No Timestamp"
        val instant = Instant.ofEpochMilli(micros / 1000L)
        return if (includeDate) ISO_FORMATTER.format(instant) else TIME_ONLY_FORMATTER.format(instant)
    }

    /**
     * Formats microsecond difference between two consecutive events as human-readable delta.
     */
    fun formatDelta(deltaMicros: Long): String {
        if (deltaMicros == 0L) return "+0ms"
        val isNegative = deltaMicros < 0
        val absMicros = if (isNegative) -deltaMicros else deltaMicros
        val prefix = if (isNegative) "-" else "+"

        return when {
            absMicros < 1_000L -> "${prefix}${absMicros}μs"
            absMicros < 1_000_000L -> "${prefix}${absMicros / 1000L}ms"
            absMicros < 60_000_000L -> {
                val sec = absMicros / 1_000_000L
                val msRemainder = (absMicros % 1_000_000L) / 100_000L
                "${prefix}${sec}.${msRemainder}s"
            }
            absMicros < 3600_000_000L -> {
                val min = absMicros / 60_000_000L
                val sec = (absMicros % 60_000_000L) / 1_000_000L
                "${prefix}${min}m ${sec}s"
            }
            else -> {
                val hours = absMicros / 3600_000_000L
                val min = (absMicros % 3600_000_000L) / 60_000_000L
                "${prefix}${hours}h ${min}m"
            }
        }
    }
}
