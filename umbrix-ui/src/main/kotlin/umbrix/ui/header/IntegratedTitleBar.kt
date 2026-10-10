package umbrix.ui.header

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.menu.HamburgerMenuContent
import umbrix.ui.menu.MenuAction
import umbrix.ui.menu.UmbrixDropdownMenu
import umbrix.ui.settings.WorkstationSettings
import umbrix.ui.theme.UmbrixTheme

/**
 * Integrated dark carbon window title bar modeled after the IntelliJ "New UI" header.
 *
 * Features:
 * - Gradient placeholder logo icon
 * - Hamburger menu button (☰) hosting the cascading [UmbrixDropdownMenu] (Phase 4)
 * - Centered active document title
 * - Draggable window surface with double-click maximize/restore toggle
 * - Clean minimize, maximize/restore, and close buttons
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun WindowScope.IntegratedTitleBar(
    windowState: WindowState,
    activeDocumentTitle: String?,
    settings: WorkstationSettings,
    onMenuAction: (MenuAction) -> Unit,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit
) {
    WindowDraggableArea(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(UmbrixTheme.colors.surfacePanel)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onDoubleClick = onToggleMaximize,
                onClick = {}
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // --- Application Logo Badge ---
            androidx.compose.foundation.Image(
                painter = umbrix.ui.icon.UmbrixIcon.titleBarPainter,
                contentDescription = "Umbrix Logo",
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(6.dp))

            // --- Hamburger Menu Button (☰) ---
            var isHamburgerHovered by remember { mutableStateOf(false) }
            var isMenuOpen by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .background(
                        color = if (isHamburgerHovered || isMenuOpen) UmbrixTheme.colors.surfaceElevated else Color.Transparent,
                        shape = RoundedCornerShape(4.dp)
                    )
                    .onPointerEvent(PointerEventType.Enter) { isHamburgerHovered = true }
                    .onPointerEvent(PointerEventType.Exit) { isHamburgerHovered = false }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { isMenuOpen = !isMenuOpen }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "☰",
                    color = if (isHamburgerHovered || isMenuOpen) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )

                // Placed inside the button so the popup anchors to the button's window bounds
                UmbrixDropdownMenu(
                    expanded = isMenuOpen,
                    onDismissRequest = { isMenuOpen = false }
                ) {
                    HamburgerMenuContent(
                        settings = settings,
                        activeDocumentTitle = activeDocumentTitle,
                        onAction = { menuAction ->
                            isMenuOpen = false
                            onMenuAction(menuAction)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // --- Window Title ---
            val displayTitle = if (!activeDocumentTitle.isNullOrBlank()) {
                "Umbrix — $activeDocumentTitle"
            } else {
                "Umbrix — Industrial Log Workstation"
            }

            Text(
                text = displayTitle,
                color = UmbrixTheme.colors.textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            // --- Window Controls (Minimize, Maximize, Close) ---
            WindowControlButton(
                symbol = "—",
                hoverColor = UmbrixTheme.colors.windowControlHover,
                onClick = onMinimize
            )

            val isMaximized = windowState.placement == WindowPlacement.Maximized
            WindowControlButton(
                symbol = if (isMaximized) "❐" else "□",
                hoverColor = UmbrixTheme.colors.windowControlHover,
                onClick = onToggleMaximize
            )

            WindowControlButton(
                symbol = "✕",
                hoverColor = UmbrixTheme.colors.windowControlHoverClose,
                hoverTextColor = Color.White,
                onClick = onClose
            )
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WindowControlButton(
    symbol: String,
    hoverColor: Color,
    hoverTextColor: Color? = null,
    onClick: () -> Unit
) {
    var isHovered by remember { mutableStateOf(false) }
    val effectiveHoverTextColor = hoverTextColor ?: UmbrixTheme.colors.textPrimary

    Box(
        modifier = Modifier
            .width(42.dp)
            .fillMaxHeight()
            .background(color = if (isHovered) hoverColor else Color.Transparent)
            .onPointerEvent(PointerEventType.Enter) { isHovered = true }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = if (isHovered) effectiveHoverTextColor else UmbrixTheme.colors.textMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal
        )
    }
}
