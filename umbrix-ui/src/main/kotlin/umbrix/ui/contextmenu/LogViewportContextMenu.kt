package umbrix.ui.contextmenu

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.theme.UmbrixTheme

/**
 * State holding active right-click context menu metadata.
 */
data class LogContextMenuState(
    val isVisible: Boolean = false,
    val physicalLine: Long = 0L,
    val lineText: String = "",
    val selectedText: String? = null,
    val positionPx: Offset = Offset.Zero
)

/**
 * Bounds-aware position provider ensuring context menu stays strictly inside the window.
 */
class ContextMenuPositionProvider(
    private val cursorOffset: Offset
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val clickX = anchorBounds.left + cursorOffset.x.toInt()
        val clickY = anchorBounds.top + cursorOffset.y.toInt()

        val x = if (clickX + popupContentSize.width > windowSize.width - 12) {
            (clickX - popupContentSize.width).coerceAtLeast(12)
        } else {
            clickX.coerceAtLeast(12)
        }

        val y = if (clickY + popupContentSize.height > windowSize.height - 12) {
            (clickY - popupContentSize.height).coerceAtLeast(12)
        } else {
            clickY.coerceAtLeast(12)
        }

        return IntOffset(x, y)
    }
}

/**
 * Lightweight floating right-click context menu anchored to a log viewport row.
 */
@Composable
fun LogViewportContextMenu(
    state: LogContextMenuState,
    onDismiss: () -> Unit,
    onCopyLineText: (String) -> Unit,
    onCopyLineNumber: (Long) -> Unit,
    onFilterByText: (String) -> Unit,
    onSearchText: (String) -> Unit,
    onSeekInScrubber: (Long) -> Unit,
    onExportStartingHere: (Long) -> Unit,
    onAddToTimeline: ((Long, String) -> Unit)? = null,
    onToggleBookmark: ((Long, String) -> Unit)? = null,
    onAppendToScratchpad: ((Long, String) -> Unit)? = null,
    onNewScratchpadFromSelection: ((String) -> Unit)? = null,
    onOpenFile: (() -> Unit)? = null,
    onNewScratchpad: (() -> Unit)? = null
) {
    if (!state.isVisible) return

    val positionProvider = remember(state.positionPx) {
        ContextMenuPositionProvider(state.positionPx)
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = true,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        Box(
            modifier = Modifier
                .width(260.dp)
                .clip(RoundedCornerShape(8.dp))
                .shadow(elevation = 16.dp, shape = RoundedCornerShape(8.dp))
                .background(UmbrixTheme.colors.surfaceElevated)
                .border(1.dp, UmbrixTheme.colors.borderFocused, RoundedCornerShape(8.dp))
                .padding(vertical = 4.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header: Line Number Indicator
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Line ${state.physicalLine + 1}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = UmbrixTheme.colors.accentPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(UmbrixTheme.colors.border)
                )

                // 1. Copy Actions
                val hasSelection = !state.selectedText.isNullOrEmpty()
                if (hasSelection) {
                    ContextMenuItem(
                        icon = "📋",
                        label = "Copy Selection",
                        shortcut = "Ctrl+C",
                        onClick = {
                            onCopyLineText(state.selectedText!!)
                            onDismiss()
                        }
                    )
                    ContextMenuItem(
                        icon = "📄",
                        label = "Copy Full Line",
                        shortcut = null,
                        onClick = {
                            onCopyLineText(state.lineText)
                            onDismiss()
                        }
                    )
                } else {
                    ContextMenuItem(
                        icon = "📋",
                        label = "Copy Full Line",
                        shortcut = "Ctrl+C",
                        onClick = {
                            onCopyLineText(state.lineText)
                            onDismiss()
                        }
                    )
                }

                // 2. Copy Line Number
                ContextMenuItem(
                    icon = "🔢",
                    label = "Copy Line Number",
                    shortcut = null,
                    onClick = {
                        onCopyLineNumber(state.physicalLine + 1)
                        onDismiss()
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(UmbrixTheme.colors.border)
                )

                // 3. Filter by Line / Selection Text
                val queryCandidate = (state.selectedText?.trim()?.take(64)
                    ?: state.lineText.trim().take(64))
                if (queryCandidate.isNotEmpty()) {
                    val labelPrefix = if (hasSelection) "Selection" else "Line"
                    ContextMenuItem(
                        icon = "⚡",
                        label = "Filter by $labelPrefix",
                        shortcut = null,
                        onClick = {
                            onFilterByText(queryCandidate)
                            onDismiss()
                        }
                    )

                    // 4. Search for This Text
                    ContextMenuItem(
                        icon = "🔍",
                        label = "Search for $labelPrefix",
                        shortcut = "Ctrl+F",
                        onClick = {
                            onSearchText(queryCandidate)
                            onDismiss()
                        }
                    )
                }

                // 5. Seek in Scrubber
                ContextMenuItem(
                    icon = "⏱",
                    label = "Seek in Temporal Scrubber",
                    shortcut = "Ctrl+T",
                    onClick = {
                        onSeekInScrubber(state.physicalLine)
                        onDismiss()
                    }
                )

                // 6. Export Sub-File Starting Here
                ContextMenuItem(
                    icon = "📦",
                    label = "Export Starting from Here...",
                    shortcut = "Ctrl+Shift+S",
                    onClick = {
                        onExportStartingHere(state.physicalLine)
                        onDismiss()
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(UmbrixTheme.colors.border)
                )

                // 7. Forensic Timeline & Bookmark Actions
                if (onAddToTimeline != null) {
                    ContextMenuItem(
                        icon = "📌",
                        label = "Add to Incident Timeline",
                        shortcut = null,
                        onClick = {
                            onAddToTimeline(state.physicalLine, state.selectedText ?: state.lineText)
                            onDismiss()
                        }
                    )
                }

                if (onToggleBookmark != null) {
                    ContextMenuItem(
                        icon = "🏷",
                        label = "Toggle Bookmark",
                        shortcut = "F2",
                        onClick = {
                            onToggleBookmark(state.physicalLine, state.lineText)
                            onDismiss()
                        }
                    )
                }

                // 8. Scratchpad Actions
                if (onAppendToScratchpad != null) {
                    ContextMenuItem(
                        icon = "📝",
                        label = "Append to Scratchpad",
                        shortcut = null,
                        onClick = {
                            onAppendToScratchpad(state.physicalLine, state.selectedText ?: state.lineText)
                            onDismiss()
                        }
                    )
                }

                if (hasSelection && onNewScratchpadFromSelection != null) {
                    ContextMenuItem(
                        icon = "📝",
                        label = "New Scratchpad from Selection",
                        shortcut = null,
                        onClick = {
                            onNewScratchpadFromSelection(state.selectedText!!)
                            onDismiss()
                        }
                    )
                }

                // 9. Workstation File & Scratchpad Actions
                if (onOpenFile != null || onNewScratchpad != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(UmbrixTheme.colors.border)
                    )
                    if (onOpenFile != null) {
                        ContextMenuItem(
                            icon = "📂",
                            label = "Open Log File...",
                            shortcut = "Ctrl+O",
                            onClick = {
                                onOpenFile()
                                onDismiss()
                            }
                        )
                    }
                    if (onNewScratchpad != null) {
                        ContextMenuItem(
                            icon = "📝",
                            label = "New Scratchpad Buffer",
                            shortcut = "Ctrl+N",
                            onClick = {
                                onNewScratchpad()
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ContextMenuItem(
    icon: String,
    label: String,
    shortcut: String?,
    onClick: () -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }
    val backgroundColor = if (isHovered) UmbrixTheme.colors.menuItemHover else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .background(color = backgroundColor, shape = RoundedCornerShape(4.dp))
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = icon, fontSize = 12.sp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            color = UmbrixTheme.colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        if (shortcut != null) {
            Text(
                text = shortcut,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = UmbrixTheme.colors.textSubtle
            )
        }
    }
}
