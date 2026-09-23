package umbrix.ui.selection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import umbrix.core.session.LogSession
import kotlin.math.max
import kotlin.math.min

/**
 * Coordinate position identifying a specific character in the document.
 */
data class TextPosition(
    val lineIndex: Long,
    val charOffset: Int
) : Comparable<TextPosition> {
    override fun compareTo(other: TextPosition): Int {
        val lineCmp = this.lineIndex.compareTo(other.lineIndex)
        return if (lineCmp != 0) lineCmp else this.charOffset.compareTo(other.charOffset)
    }
}

/**
 * Tracks interactive mouse drag partial text selection across lines and characters in the log viewport.
 */
class TextSelectionState {

    var startPosition by mutableStateOf<TextPosition?>(null)
    var endPosition by mutableStateOf<TextPosition?>(null)
    var isDraggingSelection by mutableStateOf(false)

    fun hasSelection(): Boolean {
        val start = startPosition ?: return false
        val end = endPosition ?: return false
        return start != end
    }

    fun clearSelection() {
        startPosition = null
        endPosition = null
        isDraggingSelection = false
    }

    /**
     * Normalized selection bounds where normalizedStart <= normalizedEnd.
     */
    fun normalizedRange(): Pair<TextPosition, TextPosition>? {
        val start = startPosition ?: return null
        val end = endPosition ?: return null
        return if (start <= end) Pair(start, end) else Pair(end, start)
    }

    /**
     * Resolves the highlighted character range for a given visible line index.
     * Returns null if the line has no characters selected.
     */
    fun getSelectedRangeForLine(lineIndex: Long, lineLength: Int): IntRange? {
        val (start, end) = normalizedRange() ?: return null
        if (lineIndex < start.lineIndex || lineIndex > end.lineIndex) return null
        if (lineLength <= 0) return null

        val startChar = if (lineIndex == start.lineIndex) start.charOffset.coerceIn(0, lineLength) else 0
        val endChar = if (lineIndex == end.lineIndex) end.charOffset.coerceIn(0, lineLength) else lineLength

        return if (startChar < endChar) {
            startChar until endChar
        } else {
            null
        }
    }

    /**
     * Extracts and slices the exact selected snippet across lines directly from the log session.
     */
    fun extractSelectedText(session: LogSession, maxCharsPerLine: Int = 4096): String {
        val (start, end) = normalizedRange() ?: return ""
        if (start.lineIndex == end.lineIndex) {
            val lineText = session.getLineText(start.lineIndex, maxCharsPerLine)
            val clampedStart = start.charOffset.coerceIn(0, lineText.length)
            val clampedEnd = end.charOffset.coerceIn(0, lineText.length)
            return if (clampedStart < clampedEnd) lineText.substring(clampedStart, clampedEnd) else ""
        }

        val builder = StringBuilder()
        val totalLines = session.totalLines()
        val lineLimit = min(end.lineIndex, totalLines - 1L)

        var currentLine = start.lineIndex
        while (currentLine <= lineLimit) {
            val lineText = session.getLineText(currentLine, maxCharsPerLine)
            if (currentLine == start.lineIndex) {
                val clampedStart = start.charOffset.coerceIn(0, lineText.length)
                builder.append(lineText.substring(clampedStart))
            } else if (currentLine == end.lineIndex) {
                val clampedEnd = end.charOffset.coerceIn(0, lineText.length)
                builder.append(lineText.substring(0, clampedEnd))
            } else {
                builder.append(lineText)
            }

            if (currentLine < end.lineIndex) {
                builder.append('\n')
            }
            currentLine += 1L
        }

        return builder.toString()
    }
}
