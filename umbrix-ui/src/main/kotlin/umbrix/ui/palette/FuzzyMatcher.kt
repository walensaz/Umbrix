package umbrix.ui.palette

/**
 * High-performance, linear-time subsequence fuzzy matching engine for the Command Palette.
 * ReDoS-immune with zero dynamic regular expression compilation.
 */
object FuzzyMatcher {

    private const val BASE_CHAR_SCORE = 10
    private const val CONSECUTIVE_BONUS = 15
    private const val WORD_BOUNDARY_BONUS = 25
    private const val EXACT_MATCH_BONUS = 100
    private const val PREFIX_MATCH_BONUS = 150

    /**
     * Computes a match score for [query] against [target] and secondary [keywords].
     *
     * @return Score >= 1 if matched, or 0 if query does not match target.
     */
    fun score(
        query: String,
        target: String,
        keywords: List<String> = emptyList(),
        mruBonus: Int = 0
    ): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 1 + mruBonus

        val t = target.lowercase()

        // 1. Exact match boost
        if (t == q) {
            return EXACT_MATCH_BONUS + 200 + mruBonus
        }

        // 2. Exact prefix match boost
        if (t.startsWith(q)) {
            val lengthPenalty = (t.length - q.length) / 2
            val consecutiveBonus = (q.length * (q.length - 1) / 2) * CONSECUTIVE_BONUS
            return PREFIX_MATCH_BONUS + (q.length * BASE_CHAR_SCORE) + consecutiveBonus + WORD_BOUNDARY_BONUS + mruBonus - lengthPenalty
        }

        // 3. Subsequence alignment scan
        var qIdx = 0
        var tIdx = 0
        var accumulatedScore = 0
        var consecutiveCount = 0

        while (qIdx < q.length && tIdx < t.length) {
            val qChar = q[qIdx]
            val tChar = t[tIdx]

            if (qChar == tChar) {
                accumulatedScore += BASE_CHAR_SCORE

                if (consecutiveCount > 0) {
                    accumulatedScore += consecutiveCount * CONSECUTIVE_BONUS
                }
                consecutiveCount++

                // Word boundary check on original target string
                if (tIdx == 0 || target[tIdx - 1] in " /_-.:" || target[tIdx].isUpperCase()) {
                    accumulatedScore += WORD_BOUNDARY_BONUS
                }

                qIdx++
            } else {
                consecutiveCount = 0
            }
            tIdx++
        }

        // If all query characters matched in sequence
        if (qIdx == q.length) {
            val lengthPenalty = (target.length - query.length) / 2
            return (accumulatedScore + mruBonus - lengthPenalty).coerceAtLeast(1)
        }

        // 4. Secondary keyword fallback scan
        for (keyword in keywords) {
            val kw = keyword.lowercase()
            if (kw.contains(q)) {
                return 25 + mruBonus
            }
        }

        return 0
    }

    /**
     * Matches a single [command] against [query] and returns a [ScoredCommand], or null if no match.
     */
    fun matchCommand(command: PaletteCommand, query: String, mruBonus: Int = 0): ScoredCommand? {
        val s = score(query, command.title, command.keywords, mruBonus)
        return if (s > 0) ScoredCommand(command, s) else null
    }

    /**
     * Filters [commands] by [query] and returns the matched results ranked in descending score order.
     */
    fun matchAndRank(commands: List<PaletteCommand>, query: String): List<ScoredCommand> {
        return commands.mapNotNull { matchCommand(it, query) }
            .sortedByDescending { it.score }
    }
}
