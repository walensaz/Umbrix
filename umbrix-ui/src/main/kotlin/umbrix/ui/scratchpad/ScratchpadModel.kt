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
    val createdAt: Long = System.currentTimeMillis()
    var snippetCount by mutableStateOf(if (initialContent.isNotBlank()) 1 else 0)

    val lineCount: Int
        get() = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1

    val charCount: Int
        get() = content.length

    val previewSnippet: String
        get() {
            if (content.isBlank()) return "(Empty scratchpad buffer)"
            val lines = content.lines().filter { it.isNotBlank() }
            val sample = lines.take(4).joinToString("\n")
            return if (sample.length > 250) sample.take(250) + "..." else sample
        }

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
        snippetCount++
        isDirty = true
    }

    fun saveToDisk(destinationFile: File) {
        destinationFile.writeText(content, Charsets.UTF_8)
        diskPath = destinationFile.absolutePath
        title = destinationFile.name
        isDirty = false
    }
}
