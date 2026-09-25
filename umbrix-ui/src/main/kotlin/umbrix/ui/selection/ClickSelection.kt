package umbrix.ui.selection

/** Exclusive character bounds in one displayed row. */
data class ClickSpan(val start: Int, val endExclusive: Int)

enum class ClickUnit { CHARACTER, WORD, LINE }

/** Tracks only completed, nearby primary clicks in the current text viewport. */
class ClickSequence {
    private var lastTimeMillis = -1L
    private var lastX = 0f
    private var lastY = 0f
    private var lastRow = -1L
    private var count = 0

    fun press(timeMillis: Long, x: Float, y: Float, row: Long, timeoutMillis: Long, slopPx: Float): Int {
        val delta = timeMillis - lastTimeMillis
        count = if (lastTimeMillis >= 0L && delta in 0..timeoutMillis &&
            row == lastRow && kotlin.math.abs(x - lastX) <= slopPx &&
            kotlin.math.abs(y - lastY) <= slopPx && count < 3) count + 1 else 1
        lastTimeMillis = timeMillis
        lastX = x
        lastY = y
        lastRow = row
        return count
    }

    fun cancel() {
        count = 0
        lastTimeMillis = -1L
    }
}

/** Never reads past the shaped/displayed 4,096-character row. Whitespace is not a word. */
fun clickSpan(text: String, offset: Int, unit: ClickUnit): ClickSpan? {
    val length = text.length.coerceAtMost(4096)
    if (unit == ClickUnit.LINE) return ClickSpan(0, length)
    if (offset !in 0 until length) return null
    if (unit == ClickUnit.CHARACTER) return ClickSpan(offset, offset)

    fun category(char: Char): Int = when {
        char.isLetterOrDigit() || char == '_' -> 1
        char.isWhitespace() -> 0
        else -> 2
    }

    val kind = category(text[offset])
    if (kind == 0) return null
    var start = offset
    var end = offset + 1
    while (start > 0 && category(text[start - 1]) == kind) start--
    while (end < length && category(text[end]) == kind) end++
    return ClickSpan(start, end)
}

/** Keep the original clicked unit intact, expanding inclusively by target units in either direction. */
fun extendClickSelection(
    anchorRow: Long,
    anchorSpan: ClickSpan,
    target: TextPosition,
    targetText: String,
    unit: ClickUnit
): Pair<TextPosition, TextPosition> {
    val anchorStart = TextPosition(anchorRow, anchorSpan.start)
    val anchorEnd = TextPosition(anchorRow, anchorSpan.endExclusive)
    if (target >= anchorStart && target <= anchorEnd) return anchorStart to anchorEnd

    val span = clickSpan(targetText, target.charOffset, unit)
        ?: ClickSpan(target.charOffset.coerceIn(0, targetText.length.coerceAtMost(4096)),
            target.charOffset.coerceIn(0, targetText.length.coerceAtMost(4096)))
    return if (target < anchorStart) {
        anchorEnd to TextPosition(target.lineIndex, span.start)
    } else {
        anchorStart to TextPosition(target.lineIndex, span.endExclusive)
    }
}