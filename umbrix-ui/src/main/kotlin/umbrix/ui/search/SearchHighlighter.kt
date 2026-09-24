package umbrix.ui.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.geometry.Offset
import umbrix.ui.theme.UmbrixThemeSpec
import umbrix.ui.theme.themes.DarkCarbonTheme

/**
 * Extracts individual query search terms / needles, ignoring boolean AST operators.
 */
fun extractSearchNeedles(query: String): List<String> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return emptyList()

    if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length > 2) {
        return listOf(trimmed.substring(1, trimmed.length - 1))
    }

    val tokens = trimmed.split(Regex("\\s+"))
    val needles = mutableListOf<String>()
    for (token in tokens) {
        val upper = token.uppercase()
        if (upper == "AND" || upper == "OR" || upper == "NOT") continue
        if (token.startsWith("level:", ignoreCase = true)) {
            val lvl = token.substring(6).trim()
            if (lvl.isNotBlank()) needles.add(lvl)
        } else {
            val clean = token.trim('"', '\'', '(', ')', '/', '\\')
            if (clean.isNotBlank()) needles.add(clean)
        }
    }
    return needles
}

/**
 * High-performance bounded LRU memoization cache for styled AnnotatedString lines.
 *
 * Eliminates repetitive string lowercasing, needle extraction, interval sorting, and
 * Compose span builder allocations on visible viewport rows during scrolling (60–120 FPS).
 */
object HighlightCache {
    private const val MAX_ENTRIES = 512

    private data class CacheKey(
        val text: String,
        val needlesHash: Int,
        val isMatch: Boolean,
        val isActiveMatch: Boolean,
        val themeName: String
    )

    private val lruMap = object : LinkedHashMap<CacheKey, AnnotatedString>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<CacheKey, AnnotatedString>): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized
    fun getOrBuild(
        rawText: String,
        needles: List<String>,
        isMatch: Boolean,
        isActiveMatch: Boolean,
        theme: UmbrixThemeSpec = DarkCarbonTheme
    ): AnnotatedString {
        if (!isMatch || needles.isEmpty() || rawText.isEmpty()) {
            return AnnotatedString(rawText)
        }

        val key = CacheKey(rawText, needles.hashCode(), isMatch, isActiveMatch, theme.name)
        val cached = lruMap[key]
        if (cached != null) {
            return cached
        }

        val built = buildHighlightedLineDirect(rawText, needles, isMatch, isActiveMatch, theme)
        lruMap[key] = built
        return built
    }

    @Synchronized
    fun clear() {
        lruMap.clear()
    }

    @Synchronized
    fun size(): Int = lruMap.size
}

/**
 * Builds an AnnotatedString that highlights exact search needle occurrences within a line.
 * Uses `HighlightCache` to memoize previously shaped lines for maximum scrolling FPS.
 */
fun buildHighlightedLine(
    rawText: String,
    needles: List<String>,
    isMatch: Boolean,
    isActiveMatch: Boolean,
    theme: UmbrixThemeSpec = DarkCarbonTheme
): AnnotatedString {
    return HighlightCache.getOrBuild(rawText, needles, isMatch, isActiveMatch, theme)
}

/**
 * Direct unmemoized line highlighting builder.
 */
private fun buildHighlightedLineDirect(
    rawText: String,
    needles: List<String>,
    isMatch: Boolean,
    isActiveMatch: Boolean,
    theme: UmbrixThemeSpec = DarkCarbonTheme
): AnnotatedString {
    if (!isMatch || needles.isEmpty() || rawText.isEmpty()) {
        return AnnotatedString(rawText)
    }

    val intervals = mutableListOf<Pair<Int, Int>>()
    val lowerText = rawText.lowercase()

    for (needle in needles) {
        if (needle.isBlank()) continue
        val lowerNeedle = needle.lowercase()
        var idx = lowerText.indexOf(lowerNeedle, 0)
        while (idx != -1) {
            intervals.add(Pair(idx, idx + needle.length))
            idx = lowerText.indexOf(lowerNeedle, idx + 1)
        }
    }

    if (intervals.isEmpty()) {
        return AnnotatedString(rawText)
    }

    // Sort intervals by start offset and merge overlaps
    intervals.sortBy { it.first }
    val merged = mutableListOf<Pair<Int, Int>>()
    for (interval in intervals) {
        if (merged.isEmpty()) {
            merged.add(interval)
        } else {
            val last = merged.last()
            if (interval.first <= last.second) {
                merged[merged.size - 1] = Pair(last.first, maxOf(last.second, interval.second))
            } else {
                merged.add(interval)
            }
        }
    }

    val highlightBackground = if (isActiveMatch) theme.searchHighlightActive else theme.searchHighlightInactive
    val highlightTextColor = if (isActiveMatch) theme.searchTextActive else theme.searchTextInactive
    // A diffuse shadow behind the highlight chip gives a halo that reads as visual padding
    // around the match — the blur radius is intentionally generous (4px active, 2px inactive)
    // so the orange/amber glow bleeds slightly past the character cell boundaries.
    val highlightShadow = if (isActiveMatch) {
        Shadow(
            color = theme.searchHighlightActive.copy(alpha = 0.75f),
            offset = Offset.Zero,
            blurRadius = 8f
        )
    } else {
        Shadow(
            color = theme.searchHighlightInactive.copy(alpha = 0.45f),
            offset = Offset.Zero,
            blurRadius = 4f
        )
    }

    return buildAnnotatedString {
        var cursor = 0
        for (interval in merged) {
            val start = interval.first.coerceIn(0, rawText.length)
            val end = interval.second.coerceIn(0, rawText.length)
            if (start > cursor) {
                append(rawText.substring(cursor, start))
            }
            if (end > start) {
                withStyle(
                    SpanStyle(
                        background = highlightBackground,
                        color = highlightTextColor,
                        fontWeight = FontWeight.Bold,
                        shadow = highlightShadow
                    )
                ) {
                    append(rawText.substring(start, end))
                }
            }
            cursor = maxOf(cursor, end)
        }
        if (cursor < rawText.length) {
            append(rawText.substring(cursor))
        }
    }
}

/**
 * Overlays an interactive text selection highlight onto an AnnotatedString line.
 */
fun applySelectionHighlight(
    base: AnnotatedString,
    selectedRange: IntRange?,
    selectionBackground: Color = DarkCarbonTheme.selectionBackground,
    selectionTextColor: Color = Color.White
): AnnotatedString {
    if (selectedRange == null || selectedRange.isEmpty() || base.isEmpty()) return base
    val clampedStart = selectedRange.first.coerceIn(0, base.length)
    val clampedEnd = (selectedRange.last + 1).coerceIn(0, base.length)
    if (clampedStart >= clampedEnd) return base

    return buildAnnotatedString {
        append(base)
        addStyle(
            style = SpanStyle(
                background = selectionBackground,
                color = selectionTextColor
            ),
            start = clampedStart,
            end = clampedEnd
        )
    }
}
