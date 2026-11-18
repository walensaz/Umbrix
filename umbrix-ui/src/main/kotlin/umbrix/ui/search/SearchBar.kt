package umbrix.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import umbrix.core.search.SearchResult
import umbrix.ui.components.Badge
import umbrix.ui.theme.UmbrixTheme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.text.font.FontWeight

/**
 * High-performance search toolbar modeled after the IntelliJ / Sublime Text search deck.
 *
 * Features:
 * - Query input field with shortcut key bindings (Enter / Shift+Enter / Esc)
 * - Search mode toggles with hover tooltips: Match Case (Alt+C), Match Whole Word (Alt+W), Regular Expression (Alt+R)
 * - Match navigation buttons and telemetry status badge
 * - Filtered view toggle and sub-file exporter
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SearchBar(
    queryText: String,
    onQueryChange: (String) -> Unit,
    searchResult: SearchResult?,
    errorMessage: String?,
    focusRequester: FocusRequester,
    focusTrigger: Int,
    isSearching: Boolean = false,
    isMatchCase: Boolean = false,
    onToggleMatchCase: () -> Unit = {},
    isWholeWord: Boolean = false,
    onToggleWholeWord: () -> Unit = {},
    isRegex: Boolean = false,
    onToggleRegex: () -> Unit = {},
    isFilteredView: Boolean,
    onToggleFilterView: () -> Unit,
    isBookmarkFilterActive: Boolean = false,
    bookmarkCount: Int = 0,
    onToggleBookmarkFilter: () -> Unit = {},
    onNavigateNext: () -> Unit,
    onNavigatePrev: () -> Unit,
    onExportSubFile: () -> Unit,
    onClose: () -> Unit
) {
    LaunchedEffect(focusTrigger) {
        try {
            focusRequester.requestFocus()
        } catch (_: Throwable) {
            // Defensive against unattached state in initial composition frame
        }
    }

    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(UmbrixTheme.colors.surfacePanel)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // --- 1. Query & Mode Cluster ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Search:", fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary, maxLines = 1, softWrap = false)

            TextField(
                value = queryText,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .widthIn(min = 180.dp, max = 300.dp)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            if (keyEvent.isAltPressed) {
                                when (keyEvent.key) {
                                    Key.C -> { onToggleMatchCase(); true }
                                    Key.W -> { onToggleWholeWord(); true }
                                    Key.R -> { onToggleRegex(); true }
                                    else -> false
                                }
                            } else if (keyEvent.key == Key.Escape) {
                                onClose()
                                true
                            } else if (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter) {
                                if (keyEvent.isShiftPressed) {
                                    onNavigatePrev()
                                } else {
                                    onNavigateNext()
                                }
                                true
                            } else false
                        } else false
                    }
            )

            // Search Mode Toggles (Match Case, Whole Word, Regex)
            SearchModeButton(
                label = "Aa",
                tooltip = "Match Case (Alt+C) — Require exact case match",
                isActive = isMatchCase,
                onClick = onToggleMatchCase
            )

            SearchModeButton(
                label = "|W|",
                tooltip = "Match Whole Word (Alt+W) — Match whole words only",
                isActive = isWholeWord,
                onClick = onToggleWholeWord
            )

            SearchModeButton(
                label = ".*",
                tooltip = "Regular Expression (Alt+R) — Evaluate query as a regular expression",
                isActive = isRegex,
                onClick = onToggleRegex
            )
        }

        // --- 2. Navigation & Status Cluster ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Tooltip(
                tooltip = {
                    Text("Previous Match (Shift+Enter / Shift+F3)")
                }
            ) {
                OutlinedButton(
                    onClick = {
                        onNavigatePrev()
                        try { focusRequester.requestFocus() } catch (_: Throwable) {}
                    },
                    enabled = (isSearching && queryText.isNotBlank()) || (searchResult != null && !searchResult.isEmpty())
                ) {
                    Text("↑ (Shift+Enter)", maxLines = 1, softWrap = false)
                }
            }

            Tooltip(
                tooltip = {
                    Text("Next Match (Enter / F3)")
                }
            ) {
                OutlinedButton(
                    onClick = {
                        onNavigateNext()
                        try { focusRequester.requestFocus() } catch (_: Throwable) {}
                    },
                    enabled = (isSearching && queryText.isNotBlank()) || (searchResult != null && !searchResult.isEmpty())
                ) {
                    Text("↓ (Enter)", maxLines = 1, softWrap = false)
                }
            }

            // Telemetry Badge or Error Message
            if (errorMessage != null) {
                val isWrapInfo = errorMessage.contains("wrap", ignoreCase = true)
                Badge(
                    label = if (isWrapInfo) "🔁 $errorMessage" else errorMessage,
                    textColor = if (isWrapInfo) UmbrixTheme.colors.accentPrimary else UmbrixTheme.colors.badgeErrorText,
                    backgroundColor = if (isWrapInfo) UmbrixTheme.colors.surfaceElevated else UmbrixTheme.colors.badgeErrorBackground,
                    maxWidth = 220.dp
                )
            } else if (isSearching) {
                Badge(
                    label = "Searching...",
                    textColor = UmbrixTheme.colors.badgeWarningText,
                    backgroundColor = UmbrixTheme.colors.badgeWarningBackground,
                    maxWidth = 220.dp
                )
            } else if (searchResult != null) {
                val count = searchResult.totalMatches()
                val durationMs = searchResult.searchDurationNanos() / 1_000_000L
                val suffix = if (searchResult.isComplete()) " (${durationMs}ms)" else " (searching...)"
                Badge(
                    label = "$count matches$suffix",
                    textColor = if (count > 0L) UmbrixTheme.colors.badgeSuccessText else UmbrixTheme.colors.badgeWarningText,
                    backgroundColor = if (count > 0L) UmbrixTheme.colors.badgeSuccessBackground else UmbrixTheme.colors.badgeWarningBackground,
                    maxWidth = 220.dp
                )
            }
        }

        // --- 3. Action Cluster ---
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Filter Toggle Button
            Tooltip(
                tooltip = {
                    Text(if (isFilteredView) "Show All Lines — Exit filtered view" else "Filter Matching Lines — Show only lines with search hits")
                }
            ) {
                OutlinedButton(
                    onClick = {
                        onToggleFilterView()
                        try { focusRequester.requestFocus() } catch (_: Throwable) {}
                    },
                    enabled = !isSearching && searchResult != null && !searchResult.isEmpty()
                ) {
                    Text(if (isFilteredView) "☰ Show All Lines" else "⬡ Filter Matching Lines", maxLines = 1, softWrap = false)
                }
            }

            // Bookmark Filter Toggle Button
            if (bookmarkCount > 0 || isBookmarkFilterActive) {
                Tooltip(
                    tooltip = {
                        Text(if (isBookmarkFilterActive) "Show All Lines — Exit bookmark filter" else "Filter Bookmarks — Show only bookmarked lines ($bookmarkCount)")
                    }
                ) {
                    OutlinedButton(
                        onClick = {
                            onToggleBookmarkFilter()
                            try { focusRequester.requestFocus() } catch (_: Throwable) {}
                        }
                    ) {
                        Text(if (isBookmarkFilterActive) "☰ Show All Lines" else "📌 Bookmarks ($bookmarkCount)", maxLines = 1, softWrap = false)
                    }
                }
            }

            // Export Sub-File Button
            Tooltip(
                tooltip = {
                    Text("Export Sub-File — Save matching lines to a separate file")
                }
            ) {
                DefaultButton(
                    onClick = {
                        onExportSubFile()
                        try { focusRequester.requestFocus() } catch (_: Throwable) {}
                    },
                    enabled = !isSearching && searchResult != null && !searchResult.isEmpty()
                ) {
                    Text("⤓ Export Sub-File", maxLines = 1, softWrap = false)
                }
            }

            // Close Button
            Tooltip(
                tooltip = {
                    Text("Close Search (Esc)")
                }
            ) {
                Text(
                    text = "✕",
                    fontSize = 14.sp,
                    color = UmbrixTheme.colors.textMuted,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clickable { onClose() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchModeButton(
    label: String,
    tooltip: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isActive) UmbrixTheme.colors.surfaceElevated else Color.Transparent
    val borderColor = if (isActive) UmbrixTheme.colors.accentPrimary else UmbrixTheme.colors.border
    val textColor = if (isActive) UmbrixTheme.colors.accentPrimary else UmbrixTheme.colors.textMuted
    val fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal

    Tooltip(
        tooltip = {
            Text(tooltip)
        }
    ) {
        Box(
            modifier = Modifier
                .size(width = 28.dp, height = 26.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(backgroundColor)
                .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                color = textColor,
                fontWeight = fontWeight,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
