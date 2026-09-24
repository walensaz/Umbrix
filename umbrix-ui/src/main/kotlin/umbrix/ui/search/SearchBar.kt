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
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.core.search.SearchResult
import umbrix.ui.components.Badge
import umbrix.ui.theme.UmbrixTheme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider

/**
 * High-performance search and query toolbar.
 * Decomposed into two functional tiers:
 * Tier 1: Search query input, match navigation, filter view toggle, and export.
 * Tier 2: Forensic anomaly navigation (Shift+F4/F4) and quick log level filter chips.
 */
@Composable
fun SearchBar(
    queryText: String,
    onQueryChange: (String) -> Unit,
    searchResult: SearchResult?,
    errorMessage: String?,
    focusRequester: FocusRequester,
    focusTrigger: Int,
    isFilteredView: Boolean,
    onToggleFilterView: () -> Unit,
    onNavigateNext: () -> Unit,
    onNavigatePrev: () -> Unit,
    onNavigateNextAnomaly: () -> Unit = {},
    onNavigatePrevAnomaly: () -> Unit = {},
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(UmbrixTheme.colors.surfacePanel)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // --- Tier 1: Search Query & Match Controls ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Search:", fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
            Spacer(modifier = Modifier.width(8.dp))

            TextField(
                value = queryText,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .width(360.dp)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown && (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)) {
                            if (keyEvent.isShiftPressed) {
                                onNavigatePrev()
                            } else {
                                onNavigateNext()
                            }
                            true
                        } else false
                    }
            )

            Spacer(modifier = Modifier.width(6.dp))

            OutlinedButton(
                onClick = {
                    onNavigatePrev()
                    try { focusRequester.requestFocus() } catch (_: Throwable) {}
                },
                enabled = searchResult != null && !searchResult.isEmpty()
            ) {
                Text("↑ (Shift+Enter)")
            }

            Spacer(modifier = Modifier.width(4.dp))

            OutlinedButton(
                onClick = {
                    onNavigateNext()
                    try { focusRequester.requestFocus() } catch (_: Throwable) {}
                },
                enabled = searchResult != null && !searchResult.isEmpty()
            ) {
                Text("↓ (Enter)")
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Telemetry Badge or Error Message
            if (errorMessage != null) {
                Badge(
                    label = errorMessage,
                    textColor = UmbrixTheme.colors.badgeErrorText,
                    backgroundColor = UmbrixTheme.colors.badgeErrorBackground
                )
            } else if (searchResult != null) {
                val count = searchResult.totalMatches()
                val durationMs = searchResult.searchDurationNanos() / 1_000_000L
                val suffix = if (searchResult.isComplete()) " (${durationMs}ms)" else " (searching...)"
                Badge(
                    label = "$count matches$suffix",
                    textColor = if (count > 0L) UmbrixTheme.colors.badgeSuccessText else UmbrixTheme.colors.badgeWarningText,
                    backgroundColor = if (count > 0L) UmbrixTheme.colors.badgeSuccessBackground else UmbrixTheme.colors.badgeWarningBackground
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Filter Toggle Button
            OutlinedButton(
                onClick = {
                    onToggleFilterView()
                    try { focusRequester.requestFocus() } catch (_: Throwable) {}
                },
                enabled = searchResult != null && !searchResult.isEmpty()
            ) {
                Text(if (isFilteredView) "☰ Show All Lines" else "⬡ Filter Matching Lines")
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Export Sub-File Button
            DefaultButton(
                onClick = {
                    onExportSubFile()
                    try { focusRequester.requestFocus() } catch (_: Throwable) {}
                },
                enabled = searchResult != null && !searchResult.isEmpty()
            ) {
                Text("⤓ Export Sub-File")
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Close Button
            Text(
                text = "✕",
                fontSize = 14.sp,
                color = UmbrixTheme.colors.textMuted,
                modifier = Modifier
                    .clickable { onClose() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(5.dp))
        Divider(Orientation.Horizontal)
        Spacer(modifier = Modifier.height(5.dp))

        // --- Tier 2: Forensic Anomaly Navigation & Quick Filters ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Anomalies:", fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = onNavigatePrevAnomaly
            ) {
                Text("▲ Prev Anomaly (Shift+F4)")
            }

            Spacer(modifier = Modifier.width(4.dp))

            OutlinedButton(
                onClick = onNavigateNextAnomaly
            ) {
                Text("▼ Next Anomaly (F4)")
            }

            Spacer(modifier = Modifier.width(16.dp))

            Text("Quick Filters:", fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = { onQueryChange("level:ERROR") }
            ) {
                Text("🔴 Errors")
            }

            Spacer(modifier = Modifier.width(4.dp))

            OutlinedButton(
                onClick = { onQueryChange("level:WARN") }
            ) {
                Text("🟡 Warns")
            }

            if (queryText.isNotBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { onQueryChange("") }
                ) {
                    Text("✕ Clear Query")
                }
            }
        }
    }
}
