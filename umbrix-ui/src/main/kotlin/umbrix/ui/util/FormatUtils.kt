package umbrix.ui.util

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/**
 * Option A: Native OS File Dialog for opening log files.
 */
fun showNativeOpenFileDialog(): String? {
    val fileDialog = FileDialog(null as Frame?, "Open Log File in Umbrix", FileDialog.LOAD)
    fileDialog.isVisible = true
    val directory = fileDialog.directory
    val file = fileDialog.file
    return if (directory != null && file != null) {
        File(directory, file).absolutePath
    } else {
        null
    }
}

/**
 * Native OS Save File Dialog for exporting trimmed / filtered sub-files.
 */
fun showNativeSaveFileDialog(defaultName: String): String? {
    val fileDialog = FileDialog(null as Frame?, "Save Sub-File Export", FileDialog.SAVE)
    fileDialog.file = defaultName
    fileDialog.isVisible = true
    val directory = fileDialog.directory
    val file = fileDialog.file
    return if (directory != null && file != null) {
        File(directory, file).absolutePath
    } else {
        null
    }
}

/**
 * Formats raw byte size into human-readable binary prefixes (B, KB, MB, GB, TB).
 */
fun formatByteSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(Locale.US, "%.2f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

/**
 * Formats a 64-bit integer with thousands commas for line numbers and counts.
 */
fun formatNumberWithCommas(number: Long): String {
    return NumberFormat.getNumberInstance(Locale.US).format(number)
}

/**
 * Formats epoch microseconds into wall-clock ISO-8601 or time string (UTC).
 */
fun formatEpochMicros(epochMicros: Long, includeDate: Boolean = false): String {
    val epochSeconds = epochMicros / 1_000_000L
    val remainderMicros = epochMicros % 1_000_000L
    val millis = remainderMicros / 1000L
    val dateTime = java.time.LocalDateTime.ofEpochSecond(epochSeconds, (remainderMicros * 1000).toInt(), java.time.ZoneOffset.UTC)
    return if (includeDate) {
        String.format(
            Locale.US,
            "%04d-%02d-%02d %02d:%02d:%02d.%03d",
            dateTime.year, dateTime.monthValue, dateTime.dayOfMonth,
            dateTime.hour, dateTime.minute, dateTime.second, millis
        )
    } else {
        String.format(
            Locale.US,
            "%02d:%02d:%02d.%03d",
            dateTime.hour, dateTime.minute, dateTime.second, millis
        )
    }
}

/**
 * Formats a latency gap in microseconds into a human-readable latency string.
 */
fun formatDurationMicros(micros: Long): String {
    val totalSeconds = micros / 1_000_000L
    val days = totalSeconds / 86400L
    val hours = (totalSeconds % 86400L) / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return when {
        days > 0L -> "${days}d ${hours}h ${minutes}m"
        hours > 0L -> "${hours}h ${minutes}m ${seconds}s"
        minutes > 0L -> "${minutes}m ${seconds}s"
        totalSeconds > 0L -> {
            val millis = (micros % 1_000_000L) / 1000L
            String.format(Locale.US, "%d.%02ds", totalSeconds, millis / 10L)
        }
        else -> {
            val millis = micros / 1000L
            "${millis}ms"
        }
    }
}

/**
 * Parses user-typed timestamp strings into epoch microseconds (UTC).
 * Supports full ISO-8601, 'YYYY-MM-DD HH:mm:ss[.SSS]', 'HH:mm:ss[.SSS]', or epoch millis.
 */
fun parseTimestampStringToMicros(rawInput: String, baseDateMicros: Long? = null): Long? {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return null

    // Check if user entered epoch millis (10 to 14 digits)
    if (trimmed.all { it.isDigit() } && trimmed.length in 10..15) {
        val millis = trimmed.toLongOrNull() ?: return null
        return millis * 1000L
    }

    // Try full ISO-8601 or space separated: YYYY-MM-DD[ T]HH:mm:ss[.SSS]
    try {
        val normalized = trimmed.replace(' ', 'T')
        val parsed = java.time.LocalDateTime.parse(
            if (!normalized.contains("T")) normalized else normalized,
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
        )
        return parsed.toEpochSecond(java.time.ZoneOffset.UTC) * 1_000_000L + (parsed.nano / 1000L)
    } catch (_: Throwable) {}

    // Try time-only: HH:mm:ss[.SSS] combined with base date or current UTC date
    try {
        val parsedTime = java.time.LocalTime.parse(trimmed, java.time.format.DateTimeFormatter.ISO_LOCAL_TIME)
        val baseDate = if (baseDateMicros != null && baseDateMicros > 0L) {
            val baseSeconds = baseDateMicros / 1_000_000L
            java.time.LocalDateTime.ofEpochSecond(baseSeconds, 0, java.time.ZoneOffset.UTC).toLocalDate()
        } else {
            java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        }
        val combined = java.time.LocalDateTime.of(baseDate, parsedTime)
        return combined.toEpochSecond(java.time.ZoneOffset.UTC) * 1_000_000L + (combined.nano / 1000L)
    } catch (_: Throwable) {}

    return null
}
