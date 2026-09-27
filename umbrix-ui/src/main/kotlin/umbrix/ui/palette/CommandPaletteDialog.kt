package umbrix.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.settings.CommandPaletteStyle
import umbrix.ui.settings.SettingsManager
import umbrix.ui.theme.UmbrixTheme

/**
 * Universal Command Palette dialog supporting VS Code, IntelliJ Search Everywhere,
 * Vim ex-commands, and Unified Omni personas.
 */
@OptIn(ExperimentalComposeUiApi::class)
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
                onTriggerSearch = { searchText ->
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
                    .width(640.dp)
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

                    // --- Header: Search Input Box ---
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(UmbrixTheme.colors.surfacePanel)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val iconGlyph = when {
                            query.trim().startsWith(":") -> ":"
                            query.trim().startsWith(">") -> ">"
                            else -> "🔍"
                        }

                        Text(
                            text = iconGlyph,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = UmbrixTheme.colors.accentPrimary
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        val placeholderText = when (paletteStyle) {
                            CommandPaletteStyle.VSCODE -> "Type a command, 'p' for files, or ':' for line jump..."
                            CommandPaletteStyle.INTELLIJ -> "Search everywhere (actions, files, settings)..."
                            CommandPaletteStyle.VIM -> "Enter Vim command (:w, :q, :line, :set, :theme) or search..."
                            CommandPaletteStyle.OMNI -> "Type a command ('>'), Vim (':'), line ('#'), or search..."
                        }

                        Box(modifier = Modifier.weight(1f)) {
                            if (query.isEmpty()) {
                                Text(
                                    text = placeholderText,
                                    fontSize = 13.sp,
                                    color = UmbrixTheme.colors.textSubtle
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = {
                                    query = it
                                    selectedIndex = 0
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = UmbrixTheme.colors.textPrimary,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                cursorBrush = SolidColor(UmbrixTheme.colors.accentPrimary)
                            )
                        }

                        if (query.isNotEmpty()) {
                            Text(
                                text = "✕",
                                fontSize = 12.sp,
                                color = UmbrixTheme.colors.textSubtle,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        query = ""
                                        selectedIndex = 0
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }

                    // --- IntelliJ Mode / Omni Mode: Category Filter Pills ---
                    if (paletteStyle == CommandPaletteStyle.INTELLIJ || paletteStyle == CommandPaletteStyle.OMNI) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(UmbrixTheme.colors.surfacePanel.copy(alpha = 0.5f))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            categories.forEach { cat ->
                                val isSelected = activeCategory == cat
                                val label = cat?.label ?: "All"
                                val bg = if (isSelected) UmbrixTheme.colors.accentPrimary else UmbrixTheme.colors.surfaceElevated
                                val fg = if (isSelected) Color.White else UmbrixTheme.colors.textSecondary

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(bg)
                                        .clickable {
                                            activeCategory = cat
                                            selectedIndex = 0
                                        }
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = fg
                                    )
                                }
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(UmbrixTheme.colors.border)
                    )

                    // --- Content: Vim Execution Status or Fuzzy Command List ---
                    if (vimResult is VimCommandResult.Execute) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(UmbrixTheme.colors.accentPrimary.copy(alpha = 0.12f))
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "⚡", fontSize = 16.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = vimResult.description,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = UmbrixTheme.colors.accentPrimary
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "Press Enter to run",
                                fontSize = 11.sp,
                                color = UmbrixTheme.colors.textSubtle
                            )
                        }
                    } else if (vimResult is VimCommandResult.Error) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(UmbrixTheme.colors.severityError.copy(alpha = 0.1f))
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "⚠", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = vimResult.message,
                                fontSize = 12.sp,
                                color = UmbrixTheme.colors.severityError
                            )
                        }
                    }

                    // --- Results List ---
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        if (results.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No matching commands or navigation targets found",
                                        fontSize = 12.sp,
                                        color = UmbrixTheme.colors.textSubtle
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(results) { index, scored ->
                                val isSelected = index == selectedIndex
                                val cmd = scored.command
                                val rowBg = if (isSelected) {
                                    UmbrixTheme.colors.accentPrimary.copy(alpha = 0.18f)
                                } else {
                                    UmbrixTheme.colors.surfaceElevated
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(rowBg)
                                        .clickable {
                                            cmd.action()
                                            onDismiss()
                                        }
                                        .padding(horizontal = 16.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = cmd.category.iconGlyph,
                                        fontSize = 13.sp
                                    )

                                    Spacer(modifier = Modifier.width(10.dp))

                                    Text(
                                        text = cmd.title,
                                        fontSize = 12.5.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isSelected) UmbrixTheme.colors.accentPrimary else UmbrixTheme.colors.textPrimary,
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (settings.commandPaletteShowShortcuts && !cmd.shortcutHint.isNullOrBlank()) {
                                        Box(
                                             modifier = Modifier
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(UmbrixTheme.colors.surfacePanel)
                                                .border(1.dp, UmbrixTheme.colors.border, RoundedCornerShape(3.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = cmd.shortcutHint,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = UmbrixTheme.colors.textSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // --- Footer: Status & Keyboard Cheatsheet ---
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(UmbrixTheme.colors.border)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(UmbrixTheme.colors.surfacePanel)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${results.size} items",
                            fontSize = 11.sp,
                            color = UmbrixTheme.colors.textSubtle
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "↑↓ Navigate",
                                fontSize = 10.sp,
                                color = UmbrixTheme.colors.textSubtle
                            )
                            Text(
                                text = "↵ Select",
                                fontSize = 10.sp,
                                color = UmbrixTheme.colors.textSubtle
                            )
                            Text(
                                text = "Tab Category",
                                fontSize = 10.sp,
                                color = UmbrixTheme.colors.textSubtle
                            )
                            Text(
                                text = "Esc Exit",
                                fontSize = 10.sp,
                                color = UmbrixTheme.colors.textSubtle
                            )
                        }
                    }
                }
            }
        }
    }
}
