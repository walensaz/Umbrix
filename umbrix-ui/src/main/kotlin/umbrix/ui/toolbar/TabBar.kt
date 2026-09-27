package umbrix.ui.toolbar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import umbrix.ui.menu.UmbrixDropdownMenu
import umbrix.ui.menu.UmbrixMenuGlyph
import umbrix.ui.menu.UmbrixMenuItem
import umbrix.ui.menu.UmbrixMenuSeparator
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.UmbrixTheme

/**
 * Clean IntelliJ-style Tab Bar for multi-document workstation workflows.
 */
@OptIn(ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TabBar(
    tabs: List<WorkspaceTab>,
    activeTabIndex: Int,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onNewTabClicked: () -> Unit,
    onOpenFile: (() -> Unit)? = null,
    onCloseOtherTabs: ((Int) -> Unit)? = null,
    onCloseAllTabs: (() -> Unit)? = null
) {
    var contextMenuTabIndex by remember { mutableStateOf<Int?>(null) }
    var isPlusMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(UmbrixTheme.colors.surface)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, tab ->
            val isActive = index == activeTabIndex
            val tabBackground = if (isActive) UmbrixTheme.colors.surfaceElevated else UmbrixTheme.colors.surface
            val textColor = if (isActive) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textMuted

            Box {
                Row(
                    modifier = Modifier
                        .background(tabBackground, shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .onPointerEvent(PointerEventType.Press) { event ->
                            if (event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                contextMenuTabIndex = index
                            }
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelectTab(index) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tab.title,
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "✕",
                        fontSize = 11.sp,
                        color = UmbrixTheme.colors.textSubtle,
                        modifier = Modifier.clickable { onCloseTab(index) }
                    )
                }

                UmbrixDropdownMenu(
                    expanded = contextMenuTabIndex == index,
                    onDismissRequest = { contextMenuTabIndex = null }
                ) {
                    UmbrixMenuItem(
                        text = "Close Tab",
                        shortcut = "Ctrl+W",
                        icon = { UmbrixMenuGlyph("✕") },
                        onClick = {
                            contextMenuTabIndex = null
                            onCloseTab(index)
                        }
                    )
                    if (tabs.size > 1 && onCloseOtherTabs != null) {
                        UmbrixMenuItem(
                            text = "Close Other Tabs",
                            onClick = {
                                contextMenuTabIndex = null
                                onCloseOtherTabs(index)
                            }
                        )
                    }
                    if (onCloseAllTabs != null) {
                        UmbrixMenuItem(
                            text = "Close All Tabs",
                            onClick = {
                                contextMenuTabIndex = null
                                onCloseAllTabs()
                            }
                        )
                    }
                    UmbrixMenuSeparator()
                    UmbrixMenuItem(
                        text = "New Scratchpad Buffer",
                        shortcut = "Ctrl+N",
                        icon = { UmbrixMenuGlyph("📝") },
                        onClick = {
                            contextMenuTabIndex = null
                            onNewTabClicked()
                        }
                    )
                    if (onOpenFile != null) {
                        UmbrixMenuItem(
                            text = "Open Log File...",
                            shortcut = "Ctrl+O",
                            icon = { UmbrixMenuGlyph("📂") },
                            onClick = {
                                contextMenuTabIndex = null
                                onOpenFile()
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(2.dp))
        }

        // New Tab (+) Button: Left click opens new scratchpad, right click shows options
        Box {
            Tooltip(
                tooltip = {
                    Text("New Scratchpad (Left Click) / Open Options (Right Click)")
                }
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .onPointerEvent(PointerEventType.Press) { event ->
                            if (event.buttons.isSecondaryPressed) {
                                event.changes.forEach { it.consume() }
                                isPlusMenuOpen = true
                            }
                        }
                        .clickable { onNewTabClicked() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "+",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = UmbrixTheme.colors.textMuted
                    )
                }
            }

            UmbrixDropdownMenu(
                expanded = isPlusMenuOpen,
                onDismissRequest = { isPlusMenuOpen = false }
            ) {
                UmbrixMenuItem(
                    text = "New Scratchpad Buffer",
                    shortcut = "Ctrl+N",
                    icon = { UmbrixMenuGlyph("📝") },
                    onClick = {
                        isPlusMenuOpen = false
                        onNewTabClicked()
                    }
                )
                if (onOpenFile != null) {
                    UmbrixMenuItem(
                        text = "Open Log File...",
                        shortcut = "Ctrl+O",
                        icon = { UmbrixMenuGlyph("📂") },
                        onClick = {
                            isPlusMenuOpen = false
                            onOpenFile()
                        }
                    )
                }
            }
        }
    }
}
