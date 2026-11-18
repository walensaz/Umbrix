package umbrix.plugin.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import java.awt.Cursor

/**
 * Single-line text that guarantees maxLines = 1, softWrap = false, and TextOverflow.Ellipsis
 * to prevent vertical single-character column wrapping ("totem-pole text") in narrow viewports.
 */
@Composable
fun PluginSingleLineText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    overflow: TextOverflow = TextOverflow.Ellipsis
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        maxLines = 1,
        softWrap = false,
        overflow = overflow
    )
}

/**
 * Standard styled tooltip for workstation plugins.
 * Wraps [Tooltip] with a dark surface card, subtle blue accent border, and multi-line wrapping.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PluginTooltip(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Tooltip(
        modifier = modifier,
        tooltip = {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(Color(0xFF1E232A), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF3B82F6).copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                tooltip()
            }
        }
    ) {
        content()
    }
}

/**
 * Text-based convenience overload of [PluginTooltip].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PluginTooltip(
    tooltipText: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (tooltipText.isBlank()) {
        content()
    } else {
        PluginTooltip(
            tooltip = {
                Text(
                    text = tooltipText,
                    color = Color(0xFFDFE1E5),
                    fontSize = 11.sp,
                    softWrap = true
                )
            },
            modifier = modifier,
            content = content
        )
    }
}

/**
 * Anchored popover position provider that clamps within the window boundary
 * and flips above the anchor if opening below would clip outside the window.
 */
class PluginPopoverPositionProvider(
    private val verticalGapPx: Int = 4,
    private val edgeMarginPx: Int = 8
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = maxOf(edgeMarginPx, windowSize.width - popupContentSize.width - edgeMarginPx)
        val maxY = maxOf(edgeMarginPx, windowSize.height - popupContentSize.height - edgeMarginPx)
        val x = anchorBounds.left.coerceIn(edgeMarginPx, maxX)

        val belowY = anchorBounds.bottom + verticalGapPx
        val aboveY = anchorBounds.top - popupContentSize.height - verticalGapPx
        val y = if (belowY > maxY && aboveY >= edgeMarginPx) {
            aboveY.coerceIn(edgeMarginPx, maxY)
        } else {
            belowY.coerceIn(edgeMarginPx, maxY)
        }
        return IntOffset(x, y)
    }
}

/**
 * Dropdown / Popover card anchored to its parent composable with window-edge collision clamping.
 * Never stretches parent Rows or pushes sibling controls off-screen.
 */
@Composable
fun PluginPopover(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    verticalGap: Dp = 4.dp,
    edgeMargin: Dp = 8.dp,
    focusable: Boolean = true,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val positionProvider = remember(density, verticalGap, edgeMargin) {
        PluginPopoverPositionProvider(
            verticalGapPx = with(density) { verticalGap.roundToPx() },
            edgeMarginPx = with(density) { edgeMargin.roundToPx() }
        )
    }

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = focusable,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
        Box(
            modifier = modifier
                .shadow(elevation = 12.dp, shape = RoundedCornerShape(6.dp))
                .background(Color(0xFF1E232A), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF3B82F6).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
        ) {
            content()
        }
    }
}

/**
 * Horizontal scrollable strip with subtle left and right fade gradients and clickable `<` / `>`
 * scroll buttons that appear dynamically whenever content overflows the visible bounds.
 */
@Composable
fun ScrollableStripWithOverflowIndicators(
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    backgroundColor: Color = Color(0xFF181B1F),
    scrollStep: Float = 150f,
    content: @Composable RowScope.() -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val canScrollBack = scrollState.canScrollBackward
    val canScrollFwd = scrollState.canScrollForward

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )

        // Left overflow indicator & scroll button
        if (canScrollBack) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(26.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(backgroundColor, backgroundColor.copy(alpha = 0.85f), Color.Transparent)
                        )
                    )
                    .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                    .clickable {
                        coroutineScope.launch {
                            scrollState.animateScrollBy(-scrollStep)
                        }
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "‹",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF93C5FD),
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }

        // Right overflow indicator & scroll button
        if (canScrollFwd) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(26.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, backgroundColor.copy(alpha = 0.85f), backgroundColor)
                        )
                    )
                    .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                    .clickable {
                        coroutineScope.launch {
                            scrollState.animateScrollBy(scrollStep)
                        }
                    },
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = "›",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF93C5FD),
                    modifier = Modifier.padding(end = 2.dp)
                )
            }
        }
    }
}
