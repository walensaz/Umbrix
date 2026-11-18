package umbrix.ui.scratchpad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.components.Badge
import umbrix.ui.search.extractSearchNeedles
import umbrix.ui.search.findNeedleIntervals
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.EditorTypography
import umbrix.ui.theme.UmbrixFonts
import umbrix.ui.theme.UmbrixTheme
import umbrix.ui.theme.UmbrixThemeSpec
import umbrix.ui.toast.ToastManager
import umbrix.ui.util.showNativeSaveFileDialog
import java.io.File
import kotlin.math.max

/**
 * High-performance, theme-aligned scratchpad canvas.
 * Composed with [ScratchpadToolbar] and [ScratchpadSearchReplaceDeck].
 */
@OptIn(ExperimentalComposeUiApi::class)
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
    var currentMatchIndex by remember { mutableStateOf(-1) }
    var wrapIndicator by remember { mutableStateOf<String?>(null) }
    var isWrapPending by remember { mutableStateOf(false) }

    val searchFocusRequester = remember { FocusRequester() }
    val editorFocusRequester = remember { FocusRequester() }
    val scrollState = rememberScrollState()

    var textFieldValue by remember(session.id) {
        mutableStateOf(TextFieldValue(session.content))
    }

    fun updateContent(newValue: TextFieldValue) {
        textFieldValue = newValue
        if (session.content != newValue.text) {
            session.content = newValue.text
            session.isDirty = true
            if (tab != null && tab.searchQuery.isNotBlank()) {
                ScratchpadSearchController.triggerScratchpadSearch(
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

    val activeSearchQuery = if (tab != null && tab.searchQuery.isNotBlank()) tab.searchQuery else inCanvasSearchQuery
    val activeMatchCase = if (tab != null && tab.searchQuery.isNotBlank()) tab.isSearchMatchCase else inCanvasMatchCase
    val activeWholeWord = if (tab != null && tab.searchQuery.isNotBlank()) tab.isSearchWholeWord else inCanvasWholeWord
    val activeRegex = if (tab != null && tab.searchQuery.isNotBlank()) tab.isSearchRegex else inCanvasRegex

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
        if (matchIntervals.isEmpty()) {
            currentMatchIndex = -1
            isWrapPending = false
            wrapIndicator = null
        } else if (currentMatchIndex >= matchIntervals.size) {
            currentMatchIndex = matchIntervals.size - 1
        }
    }

    LaunchedEffect(tab?.searchNavigationGeneration) {
        if (tab != null && matchIntervals.isNotEmpty()) {
            val selectedLine = tab.selectedLineIndex ?: 0L
            val selectedCol = tab.selectedMatchColumn
            val lineStarts = ScratchpadSearchController.computeLineStartOffsets(contentText)
            val selectedOffset = if (selectedLine.toInt() in lineStarts.indices && selectedCol >= 0) {
                lineStarts[selectedLine.toInt()] + selectedCol
            } else -1
            val matchIdx = if (selectedOffset >= 0) matchIntervals.indexOfFirst { it.first == selectedOffset } else -1

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

    val visualTransformation = remember(colors, matchIntervals, currentMatchIndex, activeSearchQuery) {
        VisualTransformation { original ->
            val annotated = buildAnnotatedString {
                append(original.text)
                val raw = original.text

                highlightLogLevels(raw, colors)

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
                                    color = fg
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
        val lineStarts = ScratchpadSearchController.computeLineStartOffsets(contentText)
        val lineIdx = ScratchpadSearchController.findLineIndexForOffset(lineStarts, interval.first)
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
            val nextIdx = if (currentMatchIndex < 0) 0 else currentMatchIndex + 1
            if (nextIdx < matchIntervals.size) {
                jumpToMatch(nextIdx)
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
            if (currentMatchIndex > 0) {
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
        if (matchIntervals.isEmpty()) return
        val targetIdx = if (currentMatchIndex in matchIntervals.indices) currentMatchIndex else 0
        currentMatchIndex = targetIdx
        val interval = matchIntervals[targetIdx]
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
                            val chosenPath = showNativeSaveFileDialog(session.title)
                            if (chosenPath != null) {
                                session.saveToDisk(File(chosenPath))
                                onSaveRequested()
                            }
                        }
                        true
                    } else false
                } else false
            }
    ) {
        // --- Scratchpad Toolbar ---
        ScratchpadToolbar(
            session = session,
            lineCount = lineCount,
            isSearchBarOpen = isSearchBarOpen,
            onToggleSearchBar = {
                isSearchBarOpen = !isSearchBarOpen
                if (isSearchBarOpen) isReplaceBarOpen = false
            },
            isReplaceBarOpen = isReplaceBarOpen,
            onToggleReplaceBar = {
                isSearchBarOpen = true
                isReplaceBarOpen = !isReplaceBarOpen
            },
            onSaveRequested = onSaveRequested,
            onClearRequested = {
                updateContent(TextFieldValue(""))
                session.isDirty = false
            }
        )

        // --- In-Scratchpad Search & Replace Deck ---
        ScratchpadSearchReplaceDeck(
            isSearchBarOpen = isSearchBarOpen,
            isReplaceBarOpen = isReplaceBarOpen,
            activeSearchQuery = activeSearchQuery,
            onSearchQueryChange = {
                inCanvasSearchQuery = it
                currentMatchIndex = -1
                isWrapPending = false
                wrapIndicator = null
                if (tab != null) {
                    tab.searchQuery = it
                    ScratchpadSearchController.triggerScratchpadSearch(
                        tab = tab,
                        coroutineScope = coroutineScope,
                        queryText = it,
                        immediate = false,
                        onSearchErrorMessageChange = {}
                    )
                }
            },
            activeMatchCase = activeMatchCase,
            onToggleMatchCase = {
                inCanvasMatchCase = !activeMatchCase
                currentMatchIndex = -1
                isWrapPending = false
                wrapIndicator = null
                if (tab != null) {
                    tab.isSearchMatchCase = !activeMatchCase
                    ScratchpadSearchController.triggerScratchpadSearch(
                        tab = tab,
                        coroutineScope = coroutineScope,
                        queryText = activeSearchQuery,
                        immediate = true,
                        onSearchErrorMessageChange = {}
                    )
                }
            },
            activeWholeWord = activeWholeWord,
            onToggleWholeWord = {
                inCanvasWholeWord = !activeWholeWord
                currentMatchIndex = -1
                isWrapPending = false
                wrapIndicator = null
                if (tab != null) {
                    tab.isSearchWholeWord = !activeWholeWord
                    ScratchpadSearchController.triggerScratchpadSearch(
                        tab = tab,
                        coroutineScope = coroutineScope,
                        queryText = activeSearchQuery,
                        immediate = true,
                        onSearchErrorMessageChange = {}
                    )
                }
            },
            activeRegex = activeRegex,
            onToggleRegex = {
                inCanvasRegex = !activeRegex
                currentMatchIndex = -1
                isWrapPending = false
                wrapIndicator = null
                if (tab != null) {
                    tab.isSearchRegex = !activeRegex
                    ScratchpadSearchController.triggerScratchpadSearch(
                        tab = tab,
                        coroutineScope = coroutineScope,
                        queryText = activeSearchQuery,
                        immediate = true,
                        onSearchErrorMessageChange = {}
                    )
                }
            },
            searchFocusRequester = searchFocusRequester,
            matchIntervals = matchIntervals,
            currentMatchIndex = currentMatchIndex,
            wrapIndicator = wrapIndicator,
            onNavigateMatch = { forward -> navigateMatch(forward) },
            onCloseSearchBar = {
                isSearchBarOpen = false
                inCanvasSearchQuery = ""
                currentMatchIndex = -1
                isWrapPending = false
                wrapIndicator = null
                if (tab != null) {
                    tab.searchQuery = ""
                    ScratchpadSearchController.triggerScratchpadSearch(
                        tab = tab,
                        coroutineScope = coroutineScope,
                        queryText = "",
                        immediate = true,
                        onSearchErrorMessageChange = {}
                    )
                }
            },
            replaceQuery = replaceQuery,
            onReplaceQueryChange = { replaceQuery = it },
            onPerformReplace = { performReplace() },
            onPerformReplaceAll = { performReplaceAll() }
        )

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
                val sb = StringBuilder(filteredLineIndices.size * 6)
                for (i in filteredLineIndices.indices) {
                    if (i > 0) sb.append('\n')
                    sb.append(filteredLineIndices[i] + 1)
                }
                sb.toString()
            } else {
                val sb = StringBuilder(lineCount * 6)
                for (i in 1..lineCount) {
                    if (i > 1) sb.append('\n')
                    sb.append(i)
                }
                sb.toString()
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
                                        color = colors.searchTextActive
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
                style = SpanStyle(color = color),
                start = foundIdx,
                end = endIdx
            )
            startIdx = endIdx
        }
    }
}
