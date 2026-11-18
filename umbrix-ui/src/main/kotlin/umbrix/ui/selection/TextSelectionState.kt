package umbrix.ui.selection

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class SelectionTooLargeException : IllegalArgumentException("Selection exceeds the clipboard safety limit")

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

    /** Reads displayed rows only on an explicit copy request; rejects unbounded clipboard allocations. */
    fun extractSelectedText(
        virtualLineCount: Long,
        lineTextAtVirtualIndex: (Long) -> String,
        maxCopyChars: Int = 1_048_576
    ): String {
        val (start, end) = normalizedRange() ?: return ""
        if (start.lineIndex < 0L || end.lineIndex >= virtualLineCount) return ""
        if (end.lineIndex - start.lineIndex >= 8192L) throw SelectionTooLargeException()
        val builder = StringBuilder()
        var virtualRow = start.lineIndex
        while (virtualRow <= end.lineIndex) {
            val lineText = lineTextAtVirtualIndex(virtualRow)
            val firstChar = if (virtualRow == start.lineIndex) start.charOffset.coerceIn(0, lineText.length) else 0
            val lastChar = if (virtualRow == end.lineIndex) end.charOffset.coerceIn(0, lineText.length) else lineText.length
            val length = (lastChar - firstChar).coerceAtLeast(0)
            val newline = if (virtualRow < end.lineIndex) 1 else 0
            if (length.toLong() + newline + builder.length > maxCopyChars) throw SelectionTooLargeException()
            builder.append(lineText, firstChar, firstChar + length)
            if (newline != 0) builder.append('\n')
            virtualRow++
        }
        return builder.toString()
    }
}
