package umbrix.ui.palette

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FuzzyMatcherTest {

    @Test
    fun testEmptyPatternMatchesAnything() {
        val score = FuzzyMatcher.score("", "Open File")
        assertTrue(score > 0)
    }

    @Test
    fun testExactMatchScoresHighest() {
        val exactScore = FuzzyMatcher.score("Open File", "Open File")
        val partialScore = FuzzyMatcher.score("opfl", "Open File")
        assertTrue(exactScore > partialScore)
    }

    @Test
    fun testPrefixMatchScoresHigh() {
        val prefixScore = FuzzyMatcher.score("pref", "Preferences: Switch Theme")
        val middleScore = FuzzyMatcher.score("them", "Preferences: Switch Theme")
        assertTrue(prefixScore > middleScore)
    }

    @Test
    fun testWordBoundaryBonus() {
        // "gtt" should match "Go To Timestamp" on word boundaries
        val scoreWordBoundaries = FuzzyMatcher.score("gtt", "Go To Timestamp")
        assertTrue(scoreWordBoundaries > 0)

        // Subsequence matching
        val scoreNonWord = FuzzyMatcher.score("adg", "abcdefg")
        assertTrue(scoreNonWord > 0)
    }

    @Test
    fun testNonSubsequenceDoesNotMatch() {
        assertEquals(0, FuzzyMatcher.score("xyz", "Open File"))
        assertEquals(0, FuzzyMatcher.score("fileopen", "Open File"))
    }

    @Test
    fun testKeywordsMatching() {
        val cmd = PaletteCommand(
            id = "test.export",
            title = "Export Sub-File...",
            category = CommandCategory.FILES,
            keywords = listOf("save", "slice", "extract"),
            action = {}
        )

        // Matching by title
        val scoredByTitle = FuzzyMatcher.matchCommand(cmd, "export")
        assertNotNull(scoredByTitle)

        // Matching by keyword
        val scoredByKeyword = FuzzyMatcher.matchCommand(cmd, "slice")
        assertNotNull(scoredByKeyword)

        // Non-matching
        val scoredNoMatch = FuzzyMatcher.matchCommand(cmd, "unknownquery")
        assertNull(scoredNoMatch)
    }

    @Test
    fun testFuzzySortRanksRelevanceAccurately() {
        val cmd1 = PaletteCommand(id = "1", title = "View: Toggle Minimap", category = CommandCategory.VIEW, action = {})
        val cmd2 = PaletteCommand(id = "2", title = "Minimap: Set Width to 48px", category = CommandCategory.VIEW, action = {})
        val cmd3 = PaletteCommand(id = "3", title = "File: Open File...", category = CommandCategory.FILES, action = {})

        val list = listOf(cmd1, cmd2, cmd3)
        val sorted = FuzzyMatcher.matchAndRank(list, "minimap")

        assertEquals(2, sorted.size)
        // cmd2 starts with "Minimap", so it should have higher prefix score than cmd1
        assertEquals("2", sorted[0].command.id)
        assertEquals("1", sorted[1].command.id)
    }
}
