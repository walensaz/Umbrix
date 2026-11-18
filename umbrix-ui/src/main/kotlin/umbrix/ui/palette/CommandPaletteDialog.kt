package umbrix.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import umbrix.ui.settings.CommandPaletteStyle
import umbrix.ui.settings.SettingsManager
import umbrix.ui.theme.UmbrixTheme

/**
 * Universal Command Palette dialog supporting VS Code, IntelliJ Search Everywhere,
 * Vim ex-commands, and Unified Omni personas.
 */
@Composable
fun CommandPaletteDialog(
    isVisible: Boolean,
    initialQuery: String = "",
    executionContext: CommandExecutionContext,
    onDismiss: () -> Unit
) {
    if (!isVisible) return

    val settings = SettingsManager.currentSettings
    val paletteStyle = settings.commandPaletteStyle

    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var selectedIndex by remember { mutableStateOf(0) }
    var activeCategory by remember { mutableStateOf<CommandCategory?>(null) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    // 1. Evaluate Vim Ex-Command if query starts with ':'
    val vimResult = remember(query) {
        if (query.trim().startsWith(":")) {
            VimExCommandParser.parse(
                input = query,
                onJumpToLine = { targetLine ->
                    executionContext.onJumpToDirectLine(targetLine)
                    onDismiss()
                },
                onCloseCurrentTab = {
                    executionContext.onCloseCurrentTab()
                    onDismiss()
                },
                onCloseAllTabs = {
                    executionContext.onCloseAllTabs()
                    onDismiss()
                },
                onNextTab = {
                    executionContext.onNextTab()
                    onDismiss()
                },
                onPrevTab = {
                    executionContext.onPrevTab()
                    onDismiss()
                },
                onOpenExportDialog = {
                    onDismiss()
                    executionContext.onExportSubFile()
                },
                onOpenSettingsDialog = {
                    onDismiss()
                    executionContext.onOpenSettings()
                },
                onOpenShortcutsDialog = {
                    onDismiss()
                    executionContext.onOpenShortcuts()
                },
                onTriggerSearch = { _ ->
                    onDismiss()
                    executionContext.onOpenSearch()
                }
            )
        } else {
            VimCommandResult.NotVimCommand
        }
    }

    // 2. Fuzzy match results
    val results = remember(query, activeCategory) {
        CommandRegistry.match(
            rawQuery = query,
            ctx = executionContext,
            activeCategory = activeCategory
        )
    }

    // Keep selected index within valid bounds
    LaunchedEffect(results.size) {
        if (selectedIndex >= results.size) {
            selectedIndex = maxOf(0, results.size - 1)
        }
    }

    // Auto-scroll list when selectedIndex changes
    LaunchedEffect(selectedIndex) {
        if (results.isNotEmpty() && selectedIndex in results.indices) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    // Focus text field on initial display
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val categories = remember {
        listOf(null) + CommandCategory.entries
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 72.dp)
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(12.dp))
                    .shadow(elevation = 24.dp, shape = RoundedCornerShape(12.dp))
                    .background(UmbrixTheme.colors.surfaceElevated)
                    .border(1.dp, UmbrixTheme.colors.borderFocused, RoundedCornerShape(12.dp))
                    .clickable(enabled = false) {} // Prevent click-through dismissal
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (results.isNotEmpty()) {
                                        selectedIndex = (selectedIndex + 1) % results.size
                                    }
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (results.isNotEmpty()) {
                                        selectedIndex = if (selectedIndex - 1 < 0) results.size - 1 else selectedIndex - 1
                                    }
                                    true
                                }
                                Key.PageDown -> {
                                    if (results.isNotEmpty()) {
                                        selectedIndex = (selectedIndex + 5).coerceAtMost(results.size - 1)
                                    }
                                    true
                                }
                                Key.PageUp -> {
                                    if (results.isNotEmpty()) {
                                        selectedIndex = (selectedIndex - 5).coerceAtLeast(0)
                                    }
                                    true
                                }
                                Key.Enter -> {
                                    if (vimResult is VimCommandResult.Execute) {
                                        vimResult.action()
                                        onDismiss()
                                    } else if (results.isNotEmpty() && selectedIndex in results.indices) {
                                        val cmd = results[selectedIndex].command
                                        cmd.action()
                                        onDismiss()
                                    }
                                    true
                                }
                                Key.Escape -> {
                                    onDismiss()
                                    true
                                }
                                Key.Tab -> {
                                    // Cycle category pills
                                    val currentIdx = categories.indexOf(activeCategory)
                                    val nextIdx = (currentIdx + 1) % categories.size
                                    activeCategory = categories[nextIdx]
                                    selectedIndex = 0
                                    true
                                }
                                else -> false
                            }
                        } else false
                    }
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    CommandPaletteSearchHeader(
                        query = query,
                        onQueryChange = {
                            query = it
                            selectedIndex = 0
                        },
                        paletteStyle = paletteStyle,
                        focusRequester = focusRequester
                    )

                    if (paletteStyle == CommandPaletteStyle.INTELLIJ || paletteStyle == CommandPaletteStyle.OMNI) {
                        CommandPaletteCategoryBar(
                            categories = categories,
                            activeCategory = activeCategory,
                            onSelectCategory = { cat ->
                                activeCategory = cat
                                selectedIndex = 0
                            }
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(UmbrixTheme.colors.border)
                    )

                    CommandPaletteVimBanner(vimResult = vimResult)

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        if (results.isEmpty()) {
                            item {
                                CommandPaletteEmptyState()
                            }
                        } else {
                            itemsIndexed(results) { index, scored ->
                                CommandPaletteResultRow(
                                    cmd = scored.command,
                                    isSelected = index == selectedIndex,
                                    showShortcuts = settings.commandPaletteShowShortcuts,
                                    onClick = {
                                        scored.command.action()
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(UmbrixTheme.colors.border)
                    )

                    CommandPaletteFooter(resultCount = results.size)
                }
            }
        }
    }
}
