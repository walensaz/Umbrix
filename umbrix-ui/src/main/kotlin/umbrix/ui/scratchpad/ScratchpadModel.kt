package umbrix.ui.scratchpad

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import umbrix.ui.timeline.TimelineFormatters
import java.io.File
import java.util.UUID

/**
 * An in-memory editable scratchpad buffer for capturing snippets, drafting post-mortems,
 * or comparing log slices from multiple files.
 */
class ScratchpadSession(
    val id: String = UUID.randomUUID().toString(),
    initialTitle: String = "scratch-1.log",
    initialContent: String = ""
) {
    var title by mutableStateOf(initialTitle)
    var content by mutableStateOf(initialContent)
    var isDirty by mutableStateOf(false)
    var diskPath by mutableStateOf<String?>(null)

    fun appendSnippet(
        sourceTitle: String,
        lineIndex: Long,
        timestampMicros: Long,
        snippet: String
    ) {
        val timeStr = if (timestampMicros > 0L) {
            " (${TimelineFormatters.formatTimestamp(timestampMicros, includeDate = true)})"
        } else ""

        val header = "--- $sourceTitle:${lineIndex + 1}$timeStr ---\n"
        val clamped = if (snippet.length > 4096) snippet.substring(0, 4096) else snippet
        val block = "$header$clamped\n\n"

        content = if (content.isEmpty()) block else "$content\n$block"
        isDirty = true
    }

    fun saveToDisk(destinationFile: File) {
        destinationFile.writeText(content, Charsets.UTF_8)
        diskPath = destinationFile.absolutePath
        title = destinationFile.name
        isDirty = false
    }
}
