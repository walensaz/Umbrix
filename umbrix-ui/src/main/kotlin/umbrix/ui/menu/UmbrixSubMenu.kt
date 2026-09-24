package umbrix.ui.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * Cascading submenu: a parent row that reveals a nested panel beside itself.
 *
 * - Opens after [SUBMENU_OPEN_DELAY_MS] of hover (debounced by the enclosing [MenuPanel]) or instantly on click.
 * - Positioned by [SubmenuPositionProvider]: rightward with 4.dp overlap, flipping leftward at the window edge.
 * - The nested popup is non-focusable so sibling rows in the parent panel keep receiving hover events
 *   and keyboard focus stays with the root popup, whose Escape handler collapses this panel first.
 *
 * @param text Label of the parent row.
 * @param submenuKey Stable identity within the parent panel; defaults to [text].
 * @param icon Optional leading glyph of the parent row.
 * @param content Rows rendered inside the nested panel.
 */
@Composable
fun UmbrixSubMenu(
    text: String,
    modifier: Modifier = Modifier,
    submenuKey: String = text,
    icon: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val parentLevelState = LocalMenuLevelState.current
    val isExpanded = parentLevelState?.openSubmenuKey == submenuKey

    DisposableEffect(parentLevelState, submenuKey) {
        parentLevelState?.registeredSubmenuKeys?.add(submenuKey)
        onDispose {
            parentLevelState?.registeredSubmenuKeys?.remove(submenuKey)
        }
    }

    Box(modifier = modifier) {
        UmbrixMenuItem(
            text = text,
            hoverKey = submenuKey,
            icon = icon,
            hasSubmenu = true,
            isHighlighted = isExpanded,
            onClick = {
                parentLevelState?.let { levelState ->
                    if (isExpanded) {
                        levelState.collapseSubmenu()
                    } else {
                        levelState.hoveredItemKey = submenuKey
                        levelState.openSubmenuKey = submenuKey
                    }
                }
            }
        )

        if (isExpanded) {
            val density = LocalDensity.current
            val positionProvider = remember(density) {
                with(density) {
                    SubmenuPositionProvider(overlapPx = 4.dp.roundToPx(), edgeMarginPx = 8.dp.roundToPx())
                }
            }
            val childLevelState = remember { MenuLevelState() }

            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { parentLevelState?.collapseSubmenu() },
                // Outside clicks are owned by the root popup, which tears down the whole cascade
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false)
            ) {
                MenuPanel(levelState = childLevelState, content = content)
            }
        }
    }
}
