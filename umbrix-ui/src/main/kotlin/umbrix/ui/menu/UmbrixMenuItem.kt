package umbrix.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.theme.UmbrixTheme

/**
 * High-density 32.dp menu row adhering to the Umbrix New UI design guidelines.
 *
 * @param text Primary action label.
 * @param onClick Callback invoked on pointer click or Enter while keyboard-focused.
 * @param modifier Modifier applied to the outer row.
 * @param hoverKey Identity reported to the enclosing panel's [MenuLevelState]; defaults to [text].
 * @param icon Optional leading glyph (16.dp slot).
 * @param shortcut Optional right-aligned keyboard accelerator badge (e.g. "Ctrl+O").
 * @param checked Optional checkmark toggle state (`✓`).
 * @param selected Optional radio group state (`●` / `○`).
 * @param hasSubmenu Renders the cascade arrow (`▶`).
 * @param isHighlighted Forces the hover fill (used by submenu parents while their panel is open).
 * @param enabled False renders muted, non-interactive styling.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun UmbrixMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hoverKey: String = text,
    icon: (@Composable () -> Unit)? = null,
    shortcut: String? = null,
    checked: Boolean? = null,
    selected: Boolean? = null,
    hasSubmenu: Boolean = false,
    isHighlighted: Boolean = false,
    enabled: Boolean = true
) {
    val levelState = LocalMenuLevelState.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    var isHovered by remember { mutableStateOf(false) }

    val isActive = enabled && (isHovered || isFocused || isHighlighted)
    val backgroundColor = if (isActive) UmbrixTheme.colors.menuItemHover else Color.Transparent
    val labelColor = if (enabled) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textDimmed

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(horizontal = 4.dp)
            .background(color = backgroundColor, shape = RoundedCornerShape(4.dp))
            .onPointerEvent(PointerEventType.Enter) {
                isHovered = true
                // Disabled rows still claim hover so an open sibling submenu collapses predictably
                levelState?.hoveredItemKey = hoverKey
            }
            .onPointerEvent(PointerEventType.Exit) { isHovered = false }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // --- Leading Slot: Checkmark, Radio Dot, or Icon ---
        Box(
            modifier = Modifier.size(16.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                checked != null -> {
                    if (checked) {
                        Text(
                            text = "✓",
                            color = if (enabled) UmbrixTheme.colors.accentPrimary else UmbrixTheme.colors.textDimmed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                selected != null -> {
                    Text(
                        text = if (selected) "●" else "○",
                        color = if (selected) UmbrixTheme.colors.accentPrimary else UmbrixTheme.colors.textDimmed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                icon != null -> icon()
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = text,
            color = labelColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // --- Trailing Slot: Shortcut Badge and/or Submenu Arrow ---
        if (shortcut != null) {
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = shortcut,
                color = UmbrixTheme.colors.menuShortcutText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1
            )
        }

        if (hasSubmenu) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "▶",
                color = UmbrixTheme.colors.textMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Leading glyph tinted with the theme's `menuIconTint` token. */
@Composable
fun UmbrixMenuGlyph(glyph: String, tint: Color = UmbrixTheme.colors.menuIconTint) {
    Text(text = glyph, color = tint, fontSize = 12.sp)
}
