package umbrix.ui.search

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.geometry.Offset
import com.google.re2j.Pattern
import umbrix.core.parser.QueryParser
import umbrix.ui.theme.UmbrixThemeSpec
import umbrix.ui.theme.themes.DarkCarbonTheme

/**
 * Extracts individual query search terms / needles, ignoring boolean AST operators.
 * Preserves multi-word phrases with spaces (e.g. "added to" -> ["added to"]).
 */
fun extractSearchNeedles(query: String, isRegex: Boolean = false): List<String> {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return emptyList()
    if (isRegex) return listOf(trimmed)

    return when (val parsed = QueryParser.parse(trimmed)) {
        is scala.util.Right -> {
            val needles = parsed.value().extractNeedlesJava()
            if (needles.isNotEmpty()) {
                needles
            } else {
                listOf(trimmed.removeSurrounding("\""))
            }
        }
        else -> {
            // Fallback for partial/in-progress query typing
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length > 2) {
                listOf(trimmed.substring(1, trimmed.length - 1))
            } else {
                val upper = trimmed.uppercase()
                val hasBooleanOperators = upper.contains(" AND ") || upper.contains(" OR ") ||
                    upper.contains(" NOT ") || upper.startsWith("NOT ") ||
                    trimmed.contains("&&") || trimmed.contains("||") ||
                    trimmed.startsWith("level:", ignoreCase = true)
                if (!hasBooleanOperators) {
                    listOf(trimmed.removeSurrounding("\""))
                } else {
                    val tokens = trimmed.split(Regex("\\s+"))
                    val fallbackNeedles = mutableListOf<String>()
                    for (token in tokens) {
                        val tokenUpper = token.uppercase()
                        if (tokenUpper == "AND" || tokenUpper == "OR" || tokenUpper == "NOT" || tokenUpper == "&&" || tokenUpper == "||") continue
                        if (token.startsWith("level:", ignoreCase = true)) {
                            val lvl = token.substring(6).trim()
                            if (lvl.isNotBlank()) fallbackNeedles.add(lvl)
                        } else {
                            val clean = token.trim('"', '\'', '(', ')', '/', '\\')
                            if (clean.isNotBlank()) fallbackNeedles.add(clean)
                        }
                    }
                    fallbackNeedles
                }
            }
        }
    }
}

/**
 * Finds all character intervals for search needles within [rawText], sorted and merged.
 * Supports exact match-case, whole-word boundaries (\b), and linear-time RE2J regular expressions.
 */
fun findNeedleIntervals(
    rawText: String,
    needles: List<String>,
    isMatchCase: Boolean = false,
    isWholeWord: Boolean = false,
    isRegex: Boolean = false
): List<Pair<Int, Int>> {
    if (needles.isEmpty() || rawText.isEmpty()) return emptyList()

    val intervals = mutableListOf<Pair<Int, Int>>()

    if (isRegex) {
        for (needle in needles) {
            if (needle.isBlank()) continue
            try {
                val flags = if (isMatchCase) 0 else Pattern.CASE_INSENSITIVE
                val regexPattern = if (isWholeWord) "\\b(?:$needle)\\b" else needle
                val pattern = Pattern.compile(regexPattern, flags)
                val matcher = pattern.matcher(rawText)
                while (matcher.find()) {
                    intervals.add(Pair(matcher.start(), matcher.end()))
                }
            } catch (_: Exception) {
                // Invalid/incomplete regex while user is typing
            }
        }
    } else if (isWholeWord) {
        for (needle in needles) {
            if (needle.isBlank()) continue
            try {
                val flags = if (isMatchCase) 0 else Pattern.CASE_INSENSITIVE
                val pattern = Pattern.compile("\\b" + Pattern.quote(needle) + "\\b", flags)
                val matcher = pattern.matcher(rawText)
                while (matcher.find()) {
                    intervals.add(Pair(matcher.start(), matcher.end()))
                }
            } catch (_: Exception) {}
        }
    } else if (isMatchCase) {
        for (needle in needles) {
            if (needle.isBlank()) continue
            var idx = rawText.indexOf(needle, 0)
            while (idx != -1) {
                intervals.add(Pair(idx, idx + needle.length))
                idx = rawText.indexOf(needle, idx + 1)
            }
        }
    } else {
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
    }

    if (intervals.isEmpty()) {
        return emptyList()
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
    return merged
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
        val activeMatchColumn: Int,
        val isMatchCase: Boolean,
        val isWholeWord: Boolean,
        val isRegex: Boolean,
        val showWhitespace: Boolean,
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
        isActiveMatch: Boolean = false,
        activeMatchColumn: Int = -1,
        isMatchCase: Boolean = false,
        isWholeWord: Boolean = false,
        isRegex: Boolean = false,
        showWhitespace: Boolean = false,
        theme: UmbrixThemeSpec = DarkCarbonTheme
    ): AnnotatedString {
        if ((!isMatch || needles.isEmpty()) && !showWhitespace && rawText.isNotEmpty()) {
            return AnnotatedString(rawText)
        }
        if (rawText.isEmpty()) {
            return AnnotatedString("")
        }

        val key = CacheKey(
            rawText,
            needles.hashCode(),
            isMatch,
            isActiveMatch,
            activeMatchColumn,
            isMatchCase,
            isWholeWord,
            isRegex,
            showWhitespace,
            theme.name
        )
        val cached = lruMap[key]
        if (cached != null) {
            return cached
        }

        val built = buildHighlightedLineDirect(
            rawText,
            needles,
            isMatch,
            isActiveMatch,
            activeMatchColumn,
            isMatchCase,
            isWholeWord,
            isRegex,
            showWhitespace,
            theme
        )
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
    isActiveMatch: Boolean = false,
    activeMatchColumn: Int = -1,
    isMatchCase: Boolean = false,
    isWholeWord: Boolean = false,
    isRegex: Boolean = false,
    showWhitespace: Boolean = false,
    theme: UmbrixThemeSpec = DarkCarbonTheme
): AnnotatedString {
    return HighlightCache.getOrBuild(
        rawText,
        needles,
        isMatch,
        isActiveMatch,
        activeMatchColumn,
        isMatchCase,
        isWholeWord,
        isRegex,
        showWhitespace,
        theme
    )
}

/**
 * Appends [segment] to [this] builder, substituting whitespace characters with subtle glyphs
 * (' ' -> '·', '\t' -> '→') in [whitespaceColor] when [showWhitespace] is true.
 */
private fun AnnotatedString.Builder.appendSegment(
    segment: String,
    showWhitespace: Boolean,
    whitespaceColor: Color
) {
    if (!showWhitespace) {
        append(segment)
        return
    }
    var idx = 0
    val len = segment.length
    while (idx < len) {
        val c = segment[idx]
        if (c == ' ' || c == '\t') {
            val glyph = if (c == ' ') "·" else "→"
            withStyle(SpanStyle(color = whitespaceColor)) {
                append(glyph)
            }
            idx++
        } else {
            val start = idx
            while (idx < len && segment[idx] != ' ' && segment[idx] != '\t') {
                idx++
            }
            append(segment.substring(start, idx))
        }
    }
}

/**
 * Direct unmemoized line highlighting builder.
 */
private fun buildHighlightedLineDirect(
    rawText: String,
    needles: List<String>,
    isMatch: Boolean,
    isActiveMatch: Boolean = false,
    activeMatchColumn: Int = -1,
    isMatchCase: Boolean = false,
    isWholeWord: Boolean = false,
    isRegex: Boolean = false,
    showWhitespace: Boolean = false,
    theme: UmbrixThemeSpec = DarkCarbonTheme
): AnnotatedString {
    val whitespaceColor = theme.textSubtle.copy(alpha = 0.5f)

    if (!isMatch || needles.isEmpty() || rawText.isEmpty()) {
        if (!showWhitespace || rawText.isEmpty()) {
            return AnnotatedString(rawText)
        }
        return buildAnnotatedString {
            appendSegment(rawText, showWhitespace = true, whitespaceColor)
        }
    }

    val merged = findNeedleIntervals(rawText, needles, isMatchCase, isWholeWord, isRegex)
    if (merged.isEmpty()) {
        if (!showWhitespace) {
            return AnnotatedString(rawText)
        }
        return buildAnnotatedString {
            appendSegment(rawText, showWhitespace = true, whitespaceColor)
        }
    }

    val activeShadow = Shadow(
        color = theme.searchHighlightActive.copy(alpha = 0.75f),
        offset = Offset.Zero,
        blurRadius = 8f
    )
    val inactiveShadow = Shadow(
        color = theme.searchHighlightInactive.copy(alpha = 0.45f),
        offset = Offset.Zero,
        blurRadius = 4f
    )

    return buildAnnotatedString {
        var cursor = 0
        for (interval in merged) {
            val start = interval.first.coerceIn(0, rawText.length)
            val end = interval.second.coerceIn(0, rawText.length)
            if (start > cursor) {
                appendSegment(rawText.substring(cursor, start), showWhitespace, whitespaceColor)
            }
            if (end > start) {
                // If isActiveMatch is true, only the interval matching activeMatchColumn (or all if activeMatchColumn < 0)
                // receives the prominent active styling; other intervals on the same line stay inactive.
                val isThisActive = isActiveMatch && (activeMatchColumn < 0 || (activeMatchColumn >= start && activeMatchColumn < end))
                val bg = if (isThisActive) theme.searchHighlightActive else theme.searchHighlightInactive
                val textColor = if (isThisActive) theme.searchTextActive else theme.searchTextInactive
                val shadow = if (isThisActive) activeShadow else inactiveShadow

                withStyle(
                    SpanStyle(
                        background = bg,
                        color = textColor,
                        fontWeight = FontWeight.Bold,
                        shadow = shadow
                    )
                ) {
                    append(rawText.substring(start, end))
                }
            }
            cursor = maxOf(cursor, end)
        }
        if (cursor < rawText.length) {
            appendSegment(rawText.substring(cursor), showWhitespace, whitespaceColor)
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
