package umbrix.ui.scratchpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import umbrix.ui.screen.MainScreenSearchController
import umbrix.ui.state.WorkspaceTab
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.Tooltip
import umbrix.ui.components.Badge
import umbrix.ui.search.extractSearchNeedles
import umbrix.ui.search.findNeedleIntervals
import umbrix.ui.theme.EditorTypography
import umbrix.ui.theme.UmbrixFonts
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.theme.UmbrixThemeSpec
import umbrix.ui.toast.ToastManager
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.math.max

/**
 * High-performance, theme-aligned scratchpad canvas.
 *
 * Features:
 * - Direct adherence to active [UmbrixThemeSpec] color schemes (gutter, surface, text, highlights)
 * - Configured typography via [UmbrixFonts] (font family, size, ligatures, line height)
 * - Gutter with real-time line numbering matching the main log viewport
 * - Integrated in-scratchpad Search (Ctrl+F) & Replace (Ctrl+R) with match navigation and highlights
 * - Log level syntax coloring for staged log lines (INFO, WARN, ERROR, DEBUG, TRACE, FATAL)
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ScratchpadEditorCanvas(
    session: ScratchpadSession,
    tab: WorkspaceTab? = null,
    onSaveRequested: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = UmbrixTheme.colors
    val fonts = UmbrixFonts.current
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    val editorFontFamily = remember(fonts.editorFontFamily) {
        EditorTypography.resolvedEditorFontFamily(fonts.editorFontFamily)
    }
    val fontFeatureSettings = remember(fonts.enableLigatures) {
        EditorTypography.resolveFontFeatureSettings(fonts.enableLigatures)
    }
    val lineHeightPx = with(density) {
        (fonts.editorFontSizePt * fonts.lineHeightMultiplier).sp.toPx()
    }

    var isSearchBarOpen by remember { mutableStateOf(false) }
    var isReplaceBarOpen by remember { mutableStateOf(false) }
    var inCanvasSearchQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var inCanvasMatchCase by remember { mutableStateOf(false) }
    var inCanvasWholeWord by remember { mutableStateOf(false) }
    var inCanvasRegex by remember { mutableStateOf(false) }
    var currentMatchIndex by remember { mutableStateOf(0) }
    var wrapIndicator by remember { mutableStateOf<String?>(null) }
    var isWrapPending by remember { mutableStateOf(false) }

    val searchFocusRequester = remember { FocusRequester() }
    val editorFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    // Internal text field value tracking selection and cursor
    var textFieldValue by remember(session.id) {
        mutableStateOf(TextFieldValue(session.content))
    }

    // Keep session content synchronized with text edits
    fun updateContent(newValue: TextFieldValue) {
        textFieldValue = newValue
        if (session.content != newValue.text) {
            session.content = newValue.text
            session.isDirty = true
            if (tab != null && tab.searchQuery.isNotBlank()) {
                MainScreenSearchController.triggerScratchpadSearch(
                    tab = tab,
                    coroutineScope = coroutineScope,
                    queryText = tab.searchQuery,
                    immediate = false,
                    onSearchErrorMessageChange = {}
                )
            }
        }
    }

    val contentText = textFieldValue.text
    val lineCount = remember(contentText) {
        max(1, contentText.count { it == '\n' } + 1)
    }

    // Resolve unified search settings from WorkspaceTab when available, falling back to in-canvas state
    val activeSearchQuery = if (tab != null && tab.searchQuery.isNotBlank()) {
        tab.searchQuery
    } else {
        inCanvasSearchQuery
    }
    val activeMatchCase = if (tab != null && tab.searchQuery.isNotBlank()) {
        tab.isSearchMatchCase
    } else {
        inCanvasMatchCase
    }
    val activeWholeWord = if (tab != null && tab.searchQuery.isNotBlank()) {
        tab.isSearchWholeWord
    } else {
        inCanvasWholeWord
    }
    val activeRegex = if (tab != null && tab.searchQuery.isNotBlank()) {
        tab.isSearchRegex
    } else {
        inCanvasRegex
    }

    // Resolve search match intervals within the scratchpad text
    val matchIntervals = remember(contentText, activeSearchQuery, activeMatchCase, activeWholeWord, activeRegex) {
        if (activeSearchQuery.isBlank()) {
            emptyList()
        } else {
            val needles = extractSearchNeedles(activeSearchQuery, isRegex = activeRegex)
            findNeedleIntervals(
                rawText = contentText,
                needles = needles,
                isMatchCase = activeMatchCase,
                isWholeWord = activeWholeWord,
                isRegex = activeRegex
            )
        }
    }

    LaunchedEffect(matchIntervals.size) {
        if (matchIntervals.isNotEmpty()) {
            currentMatchIndex = currentMatchIndex.coerceIn(0, matchIntervals.size - 1)
        } else {
            currentMatchIndex = 0
            isWrapPending = false
            wrapIndicator = null
        }
    }

    // Watch external search navigation events from the main workstation SearchBar / F3
    LaunchedEffect(tab?.searchNavigationGeneration) {
        if (tab != null && matchIntervals.isNotEmpty()) {
            val selectedLine = tab.selectedLineIndex ?: 0L
            val selectedCol = tab.selectedMatchColumn
            val lineStarts = MainScreenSearchController.computeLineStartOffsets(contentText)
            val selectedOffset = if (selectedLine.toInt() in lineStarts.indices && selectedCol >= 0) {
                lineStarts[selectedLine.toInt()] + selectedCol
            } else {
                -1
            }
            val matchIdx = if (selectedOffset >= 0) {
                matchIntervals.indexOfFirst { it.first == selectedOffset }
            } else -1

            if (matchIdx >= 0) {
                currentMatchIndex = matchIdx
                val interval = matchIntervals[matchIdx]
                textFieldValue = textFieldValue.copy(
                    selection = TextRange(interval.first, interval.second)
                )
                val targetScrollY = (selectedLine * lineHeightPx - 150f).coerceAtLeast(0f).toInt()
                coroutineScope.launch {
                    scrollState.animateScrollTo(targetScrollY)
                }
            }
        }
    }

    // Visual transformation for log level highlights and active search needles
    val visualTransformation = remember(colors, matchIntervals, currentMatchIndex, activeSearchQuery) {
        VisualTransformation { original ->
            val annotated = buildAnnotatedString {
                append(original.text)
                val raw = original.text

                // 1. Syntax highlight log levels
                highlightLogLevels(raw, colors)

                // 2. Highlight all search needle matches
                if (matchIntervals.isNotEmpty()) {
                    matchIntervals.forEachIndexed { idx, interval ->
                        val start = interval.first.coerceIn(0, raw.length)
                        val end = interval.second.coerceIn(0, raw.length)
                        if (start < end) {
                            val isCurrent = idx == currentMatchIndex
                            val bg = if (isCurrent) colors.searchHighlightActive else colors.searchHighlightInactive
                            val fg = if (isCurrent) colors.searchTextActive else colors.searchTextInactive
                            addStyle(
                                style = SpanStyle(
                                    background = bg,
                                    color = fg,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                ),
                                start = start,
                                end = end
                            )
                        }
                    }
                }
            }
            TransformedText(annotated, OffsetMapping.Identity)
        }
    }

    fun jumpToMatch(index: Int) {
        if (matchIntervals.isEmpty()) return
        val clampedIndex = index.coerceIn(0, matchIntervals.size - 1)
        currentMatchIndex = clampedIndex
        val interval = matchIntervals[clampedIndex]
        textFieldValue = textFieldValue.copy(
            selection = TextRange(interval.first, interval.second)
        )
        val lineStarts = MainScreenSearchController.computeLineStartOffsets(contentText)
        val lineIdx = MainScreenSearchController.findLineIndexForOffset(lineStarts, interval.first)
        val col = interval.first - lineStarts[lineIdx]
        if (tab != null) {
            tab.selectedLineIndex = lineIdx.toLong()
            tab.selectedMatchColumn = col
        }
        val targetScrollY = (lineIdx * lineHeightPx - 150f).coerceAtLeast(0f).toInt()
        coroutineScope.launch {
            scrollState.animateScrollTo(targetScrollY)
        }
    }

    fun navigateMatch(forward: Boolean) {
        if (matchIntervals.isEmpty()) return
        if (forward) {
            if (currentMatchIndex + 1 < matchIntervals.size) {
                jumpToMatch(currentMatchIndex + 1)
                isWrapPending = false
                wrapIndicator = null
            } else {
                if (!isWrapPending) {
                    isWrapPending = true
                    wrapIndicator = "Reached end. Press Enter to wrap to top."
                    ToastManager.info("Reached end of scratchpad. Press Enter to wrap to top.")
                } else {
                    jumpToMatch(0)
                    isWrapPending = false
                    wrapIndicator = "Wrapped to top"
                    ToastManager.info("Wrapped to top (Match 1 of ${matchIntervals.size})")
                }
            }
        } else {
            if (currentMatchIndex - 1 >= 0) {
                jumpToMatch(currentMatchIndex - 1)
                isWrapPending = false
                wrapIndicator = null
            } else {
                if (!isWrapPending) {
                    isWrapPending = true
                    wrapIndicator = "Reached start. Press Shift+Enter to wrap to bottom."
                    ToastManager.info("Reached start of scratchpad. Press Shift+Enter to wrap to bottom.")
                } else {
                    jumpToMatch(matchIntervals.size - 1)
                    isWrapPending = false
                    wrapIndicator = "Wrapped to bottom"
                    ToastManager.info("Wrapped to bottom (Match ${matchIntervals.size} of ${matchIntervals.size})")
                }
            }
        }
    }

    fun performReplace() {
        if (matchIntervals.isEmpty() || currentMatchIndex !in matchIntervals.indices) return
        val interval = matchIntervals[currentMatchIndex]
        val newText = contentText.substring(0, interval.first) + replaceQuery + contentText.substring(interval.second)
        updateContent(TextFieldValue(newText, selection = TextRange(interval.first + replaceQuery.length)))
        ToastManager.info("Replaced 1 occurrence")
    }

    fun performReplaceAll() {
        if (matchIntervals.isEmpty()) return
        val count = matchIntervals.size
        val effectiveRegex = when {
            activeRegex -> Regex(activeSearchQuery, if (activeMatchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
            activeWholeWord -> Regex("\\b${Regex.escape(activeSearchQuery)}\\b", if (activeMatchCase) emptySet() else setOf(RegexOption.IGNORE_CASE))
            else -> null
        }
        val newText = if (effectiveRegex != null) {
            contentText.replace(effectiveRegex, replaceQuery)
        } else {
            contentText.replace(activeSearchQuery, replaceQuery, ignoreCase = !activeMatchCase)
        }
        updateContent(TextFieldValue(newText))
        ToastManager.success("Replaced $count occurrences")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surface)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    val isCtrl = keyEvent.isCtrlPressed || keyEvent.isMetaPressed
                    if (isCtrl && keyEvent.key == Key.F) {
                        isSearchBarOpen = true
                        isReplaceBarOpen = false
                        true
                    } else if (isCtrl && (keyEvent.key == Key.R || keyEvent.key == Key.H)) {
                        isSearchBarOpen = true
                        isReplaceBarOpen = true
                        true
                    } else if (isCtrl && keyEvent.key == Key.S) {
                        if (session.diskPath != null) {
                            session.saveToDisk(File(session.diskPath!!))
                            onSaveRequested()
                        } else {
                            val dialog = FileDialog(null as Frame?, "Save Scratchpad", FileDialog.SAVE)
                            dialog.file = session.title
                            dialog.isVisible = true
                            val dir = dialog.directory
                            val filename = dialog.file
                            if (dir != null && filename != null) {
                                session.saveToDisk(File(dir, filename))
                                onSaveRequested()
                            }
                        }
                        true
                    } else false
                } else false
            }
    ) {
        // --- Scratchpad Toolbar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceElevated)
                .border(1.dp, colors.border)
                .padding(horizontal = 12.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📝", fontSize = 13.sp)
                Text(
                    session.title + if (session.isDirty) " *" else "",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary
                )
                Badge(
                    label = "$lineCount lines",
                    textColor = colors.badgeLinesText,
                    backgroundColor = colors.badgeLinesBackground
                )
                if (session.diskPath != null) {
                    Text(
                        "(${session.diskPath})",
                        fontSize = 10.sp,
                        color = colors.textSecondary
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = {
                    isSearchBarOpen = !isSearchBarOpen
                    if (isSearchBarOpen) isReplaceBarOpen = false
                }) {
                    Text("🔍 Find (Ctrl+F)", fontSize = 11.sp)
                }

                OutlinedButton(onClick = {
                    isSearchBarOpen = true
                    isReplaceBarOpen = !isReplaceBarOpen
                }) {
                    Text("⇄ Replace (Ctrl+R)", fontSize = 11.sp)
                }

                DefaultButton(onClick = {
                    if (session.diskPath != null) {
                        session.saveToDisk(File(session.diskPath!!))
                        onSaveRequested()
                    } else {
                        val dialog = FileDialog(null as Frame?, "Save Scratchpad", FileDialog.SAVE)
                        dialog.file = session.title
                        dialog.isVisible = true
                        val dir = dialog.directory
                        val filename = dialog.file
                        if (dir != null && filename != null) {
                            session.saveToDisk(File(dir, filename))
                            onSaveRequested()
                        }
                    }
                }) {
                    Text("Save (Ctrl+S)", fontSize = 11.sp)
                }

                OutlinedButton(onClick = {
                    updateContent(TextFieldValue(""))
                    session.isDirty = false
                }) {
                    Text("Clear", fontSize = 11.sp)
                }
            }
        }

        // --- In-Scratchpad Search & Replace Deck ---
        if (isSearchBarOpen) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfacePanel)
                    .border(1.dp, colors.border)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Find:", fontSize = 11.5.sp, color = colors.textSecondary)
                    Spacer(modifier = Modifier.width(6.dp))

                    TextField(
                        value = activeSearchQuery,
                        onValueChange = {
                            inCanvasSearchQuery = it
                            isWrapPending = false
                            wrapIndicator = null
                            if (tab != null) {
                                tab.searchQuery = it
                                MainScreenSearchController.triggerScratchpadSearch(
                                    tab = tab,
                                    coroutineScope = coroutineScope,
                                    queryText = it,
                                    immediate = false,
                                    onSearchErrorMessageChange = {}
                                )
                            }
                        },
                        modifier = Modifier
                            .width(260.dp)
                            .focusRequester(searchFocusRequester)
                            .onPreviewKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    if (keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter) {
                                        if (keyEvent.isShiftPressed) {
                                            navigateMatch(forward = false)
                                        } else {
                                            navigateMatch(forward = true)
                                        }
                                        true
                                    } else if (keyEvent.key == Key.Escape) {
                                        isSearchBarOpen = false
                                        true
                                    } else false
                                } else false
                            }
                    )

                    Spacer(modifier = Modifier.width(5.dp))

                    // Match Case Toggle
                    ScratchModeButton(
                        label = "Aa",
                        tooltip = "Match Case (Alt+C)",
                        isActive = activeMatchCase,
                        onClick = {
                            inCanvasMatchCase = !activeMatchCase
                            if (tab != null) {
                                tab.isSearchMatchCase = !activeMatchCase
                                MainScreenSearchController.triggerScratchpadSearch(
                                    tab = tab,
                                    coroutineScope = coroutineScope,
                                    queryText = activeSearchQuery,
                                    immediate = true,
                                    onSearchErrorMessageChange = {}
                                )
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(3.dp))

                    // Whole Word Toggle
                    ScratchModeButton(
                        label = "|W|",
                        tooltip = "Match Whole Word (Alt+W)",
                        isActive = activeWholeWord,
                        onClick = {
                            inCanvasWholeWord = !activeWholeWord
                            if (tab != null) {
                                tab.isSearchWholeWord = !activeWholeWord
                                MainScreenSearchController.triggerScratchpadSearch(
                                    tab = tab,
                                    coroutineScope = coroutineScope,
                                    queryText = activeSearchQuery,
                                    immediate = true,
                                    onSearchErrorMessageChange = {}
                                )
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(3.dp))

                    // Regex Toggle
                    ScratchModeButton(
                        label = ".*",
                        tooltip = "Regular Expression (Alt+R)",
                        isActive = activeRegex,
                        onClick = {
                            inCanvasRegex = !activeRegex
                            if (tab != null) {
                                tab.isSearchRegex = !activeRegex
                                MainScreenSearchController.triggerScratchpadSearch(
                                    tab = tab,
                                    coroutineScope = coroutineScope,
                                    queryText = activeSearchQuery,
                                    immediate = true,
                                    onSearchErrorMessageChange = {}
                                )
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Match Navigation Buttons
                    OutlinedButton(
                        onClick = { navigateMatch(forward = false) },
                        enabled = matchIntervals.isNotEmpty()
                    ) {
                        Text("↑", fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.width(3.dp))

                    OutlinedButton(
                        onClick = { navigateMatch(forward = true) },
                        enabled = matchIntervals.isNotEmpty()
                    ) {
                        Text("↓", fontSize = 11.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Telemetry / Wrap Badge
                    if (wrapIndicator != null) {
                        Badge(
                            label = "🔁 $wrapIndicator",
                            textColor = colors.accentPrimary,
                            backgroundColor = colors.surfaceElevated
                        )
                    } else if (activeSearchQuery.isNotBlank()) {
                        val count = matchIntervals.size
                        val currentStr = if (count > 0) "${currentMatchIndex + 1} of " else ""
                        Badge(
                            label = "$currentStr$count matches",
                            textColor = if (count > 0) colors.badgeSuccessText else colors.badgeWarningText,
                            backgroundColor = if (count > 0) colors.badgeSuccessBackground else colors.badgeWarningBackground
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Text(
                        text = "✕",
                        fontSize = 13.sp,
                        color = colors.textMuted,
                        modifier = Modifier
                            .clickable {
                                isSearchBarOpen = false
                                inCanvasSearchQuery = ""
                                if (tab != null) {
                                    tab.searchQuery = ""
                                    MainScreenSearchController.triggerScratchpadSearch(
                                        tab = tab,
                                        coroutineScope = coroutineScope,
                                        queryText = "",
                                        immediate = true,
                                        onSearchErrorMessageChange = {}
                                    )
                                }
                            }
                            .padding(4.dp)
                    )
                }

                // Replace Row (if Ctrl+R toggled)
                if (isReplaceBarOpen) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Replace:", fontSize = 11.5.sp, color = colors.textSecondary)
                        Spacer(modifier = Modifier.width(6.dp))

                        TextField(
                            value = replaceQuery,
                            onValueChange = { replaceQuery = it },
                            modifier = Modifier.width(260.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        OutlinedButton(
                            onClick = { performReplace() },
                            enabled = matchIntervals.isNotEmpty()
                        ) {
                            Text("Replace", fontSize = 11.sp)
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        DefaultButton(
                            onClick = { performReplaceAll() },
                            enabled = matchIntervals.isNotEmpty()
                        ) {
                            Text("Replace All", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // --- Filtered View Logic ---
        val isFiltered = tab?.isFilteredView == true && tab?.searchResult != null && !tab.searchResult!!.isEmpty
        val matchingLinesBitmap = remember(tab?.searchResult) { tab?.searchResult?.matchingLines() }
        val allContentLines = remember(contentText) { contentText.lines() }
        val filteredLineIndices = remember(allContentLines.size, matchingLinesBitmap, isFiltered) {
            if (isFiltered && matchingLinesBitmap != null) {
                allContentLines.indices.filter { matchingLinesBitmap.contains(it.toLong()) }
            } else null
        }

        // --- Filtered View Notification Banner ---
        if (isFiltered && filteredLineIndices != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.accentPrimary.copy(alpha = 0.5f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Badge(
                        label = "FILTERED",
                        textColor = colors.badgeSuccessText,
                        backgroundColor = colors.badgeSuccessBackground
                    )
                    Text(
                        text = "Showing ${filteredLineIndices.size} matching lines only",
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                OutlinedButton(onClick = { tab?.isFilteredView = false }) {
                    Text("Show All Lines", fontSize = 11.sp)
                }
            }
        }

        // --- Editor Canvas with Gutter & Hardware-Native Text ---
        val lineNumbersText = remember(lineCount, isFiltered, filteredLineIndices) {
            if (isFiltered && filteredLineIndices != null) {
                filteredLineIndices.map { it + 1 }.joinToString("\n")
            } else {
                (1..lineCount).joinToString("\n")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(colors.surface)
        ) {
            // Line Numbers Gutter Column
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(colors.surfaceGutter)
                    .border(1.dp, colors.gutterBorder)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = lineNumbersText,
                    style = TextStyle(
                        fontFamily = editorFontFamily,
                        fontSize = fonts.editorFontSizePt.sp,
                        fontFeatureSettings = fontFeatureSettings,
                        color = colors.gutterLineNormal,
                        textAlign = TextAlign.End,
                        lineHeight = (fonts.editorFontSizePt * fonts.lineHeightMultiplier).sp
                    )
                )
            }

            // Text Surface (Filtered read-only lines or Editable BasicTextField)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .verticalScroll(scrollState)
            ) {
                if (isFiltered && filteredLineIndices != null) {
                    val filteredLinesText = remember(filteredLineIndices, allContentLines) {
                        filteredLineIndices.map { allContentLines[it] }.joinToString("\n")
                    }
                    val filteredAnnotatedString = remember(filteredLinesText, colors, activeSearchQuery, activeMatchCase, activeWholeWord, activeRegex) {
                        buildAnnotatedString {
                            append(filteredLinesText)
                            highlightLogLevels(filteredLinesText, colors)
                            val needles = extractSearchNeedles(activeSearchQuery, isRegex = activeRegex)
                            val intervals = findNeedleIntervals(
                                rawText = filteredLinesText,
                                needles = needles,
                                isMatchCase = activeMatchCase,
                                isWholeWord = activeWholeWord,
                                isRegex = activeRegex
                            )
                            intervals.forEach { interval ->
                                addStyle(
                                    style = SpanStyle(
                                        background = colors.searchHighlightActive,
                                        color = colors.searchTextActive,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    start = interval.first,
                                    end = interval.second
                                )
                            }
                        }
                    }
                    Text(
                        text = filteredAnnotatedString,
                        style = TextStyle(
                            fontFamily = editorFontFamily,
                            fontSize = fonts.editorFontSizePt.sp,
                            fontFeatureSettings = fontFeatureSettings,
                            color = colors.textEditor,
                            lineHeight = (fonts.editorFontSizePt * fonts.lineHeightMultiplier).sp
                        ),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { updateContent(it) },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(editorFocusRequester),
                        textStyle = TextStyle(
                            fontFamily = editorFontFamily,
                            fontSize = fonts.editorFontSizePt.sp,
                            fontFeatureSettings = fontFeatureSettings,
                            color = colors.textEditor,
                            lineHeight = (fonts.editorFontSizePt * fonts.lineHeightMultiplier).sp
                        ),
                        cursorBrush = SolidColor(colors.accentPrimary),
                        visualTransformation = visualTransformation
                    )
                }
            }
        }
    }
}

/**
 * Highlights standard log levels within the scratchpad text.
 */
private fun AnnotatedString.Builder.highlightLogLevels(text: String, colors: UmbrixThemeSpec) {
    val logKeywords = listOf(
        "ERROR" to colors.severityError,
        "FATAL" to colors.severityError,
        "WARN" to colors.severityWarn,
        "WARNING" to colors.severityWarn,
        "INFO" to colors.severityInfo,
        "DEBUG" to colors.severityDebug,
        "TRACE" to colors.severityTrace
    )

    for (item in logKeywords) {
        val keyword = item.first
        val color = item.second
        var startIdx = 0
        while (startIdx < text.length) {
            val foundIdx = text.indexOf(keyword, startIdx, ignoreCase = false)
            if (foundIdx < 0) break
            val endIdx = foundIdx + keyword.length
            addStyle(
                style = SpanStyle(color = color, fontWeight = FontWeight.Bold),
                start = foundIdx,
                end = endIdx
            )
            startIdx = endIdx
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ScratchModeButton(
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
                color = text
            )
        }
    }
}
