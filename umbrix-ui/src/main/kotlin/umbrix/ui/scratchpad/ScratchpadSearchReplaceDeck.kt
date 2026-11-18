package umbrix.ui.scratchpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import umbrix.ui.components.Badge
import umbrix.ui.theme.UmbrixTheme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.widthIn

/**
 * Toggle button for scratchpad search flags (Match Case, Whole Word, Regex).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ScratchModeButton(
    label: String,
    tooltip: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val colors = UmbrixTheme.colors
    val bg = if (isActive) colors.surfaceElevated else Color.Transparent
    val border = if (isActive) colors.accentPrimary else colors.border
    val text = if (isActive) colors.accentPrimary else colors.textMuted

    Tooltip(tooltip = { Text(tooltip) }) {
        Box(
            modifier = Modifier
                .size(width = 26.dp, height = 24.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(bg)
                .border(1.dp, border, RoundedCornerShape(3.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = text,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * In-scratchpad Find & Replace deck extracted from [ScratchpadEditorCanvas].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScratchpadSearchReplaceDeck(
    isSearchBarOpen: Boolean,
    isReplaceBarOpen: Boolean,
    activeSearchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    activeMatchCase: Boolean,
    onToggleMatchCase: () -> Unit,
    activeWholeWord: Boolean,
    onToggleWholeWord: () -> Unit,
    activeRegex: Boolean,
    onToggleRegex: () -> Unit,
    searchFocusRequester: FocusRequester,
    matchIntervals: List<Pair<Int, Int>>,
    currentMatchIndex: Int,
    wrapIndicator: String?,
    onNavigateMatch: (forward: Boolean) -> Unit,
    onCloseSearchBar: () -> Unit,
    replaceQuery: String,
    onReplaceQueryChange: (String) -> Unit,
    onPerformReplace: () -> Unit,
    onPerformReplaceAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isSearchBarOpen) return

    val colors = UmbrixTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfacePanel)
            .border(1.dp, colors.border)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Find:", fontSize = 11.5.sp, color = colors.textSecondary, maxLines = 1, softWrap = false)

                TextField(
                    value = activeSearchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier
                        .widthIn(min = 160.dp, max = 260.dp)
                        .focusRequester(searchFocusRequester)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                if (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter) {
                                    onNavigateMatch(!keyEvent.isShiftPressed)
                                    true
                                } else if (keyEvent.key == Key.Escape) {
                                    onCloseSearchBar()
                                    true
                                } else false
                            } else false
                        }
                )

                // Match Case Toggle
                ScratchModeButton(
                    label = "Aa",
                    tooltip = "Match Case (Alt+C)",
                    isActive = activeMatchCase,
                    onClick = onToggleMatchCase
                )

                // Whole Word Toggle
                ScratchModeButton(
                    label = "|W|",
                    tooltip = "Match Whole Word (Alt+W)",
                    isActive = activeWholeWord,
                    onClick = onToggleWholeWord
                )

                // Regex Toggle
                ScratchModeButton(
                    label = ".*",
                    tooltip = "Regular Expression (Alt+R)",
                    isActive = activeRegex,
                    onClick = onToggleRegex
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Match Navigation Buttons
                OutlinedButton(
                    onClick = { onNavigateMatch(false) },
                    enabled = matchIntervals.isNotEmpty()
                ) {
                    Text("↑", fontSize = 11.sp, maxLines = 1, softWrap = false)
                }

                OutlinedButton(
                    onClick = { onNavigateMatch(true) },
                    enabled = matchIntervals.isNotEmpty()
                ) {
                    Text("↓", fontSize = 11.sp, maxLines = 1, softWrap = false)
                }

                // Telemetry / Wrap Badge
                if (wrapIndicator != null) {
                    Badge(
                        label = "🔁 $wrapIndicator",
                        textColor = colors.accentPrimary,
                        backgroundColor = colors.surfaceElevated,
                        maxWidth = 200.dp
                    )
                } else if (activeSearchQuery.isNotBlank()) {
                    val count = matchIntervals.size
                    val currentStr = if (count > 0 && currentMatchIndex in 0 until count) "${currentMatchIndex + 1} of " else ""
                    Badge(
                        label = "$currentStr$count matches",
                        textColor = if (count > 0) colors.badgeSuccessText else colors.badgeWarningText,
                        backgroundColor = if (count > 0) colors.badgeSuccessBackground else colors.badgeWarningBackground,
                        maxWidth = 200.dp
                    )
                }

                Text(
                    text = "✕",
                    fontSize = 13.sp,
                    color = colors.textMuted,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .clickable { onCloseSearchBar() }
                        .padding(4.dp)
                )
            }
        }

        // Replace Row (if Ctrl+R toggled)
        if (isReplaceBarOpen) {
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Replace:", fontSize = 11.5.sp, color = colors.textSecondary, maxLines = 1, softWrap = false)

                    TextField(
                        value = replaceQuery,
                        onValueChange = onReplaceQueryChange,
                        modifier = Modifier.widthIn(min = 160.dp, max = 260.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = onPerformReplace,
                        enabled = matchIntervals.isNotEmpty()
                    ) {
                        Text("Replace", fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }

                    DefaultButton(
                        onClick = onPerformReplaceAll,
                        enabled = matchIntervals.isNotEmpty()
                    ) {
                        Text("Replace All", fontSize = 11.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
}
