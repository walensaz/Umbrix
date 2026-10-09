package umbrix.ui.docking

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import umbrix.ui.theme.UmbrixTheme
import java.awt.Cursor

/**
 * Resizable split divider slider between two docking nodes.
 * Features an ergonomic 11dp hit area, OS resize cursors, hover glow,
 * ultra-smooth continuous proportional drag resizing clamped between 5% and 95%,
 * double-click 50/50 balance reset, and an optional synchronized scrolling toggle badge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DockSplitDivider(
    orientation: DockOrientation,
    splitRatio: Float,
    totalDimensionPx: Float,
    onSplitRatioChanged: (Float) -> Unit,
    isSyncScrollEnabled: Boolean = false,
    onToggleSyncScroll: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var isDragging by remember { mutableStateOf(false) }

    val currentTotalDimensionPx by rememberUpdatedState(totalDimensionPx)
    val currentSplitRatio by rememberUpdatedState(splitRatio)
    val onRatioChangedUpdated by rememberUpdatedState(onSplitRatioChanged)

    val cursor = remember(orientation) {
        if (orientation == DockOrientation.HORIZONTAL) {
            PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))
        } else {
            PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR))
        }
    }

    // Generous 11dp hit target (5dp on each side of the centered hairline)
    val hitModifier = if (orientation == DockOrientation.HORIZONTAL) {
        Modifier
            .width(11.dp)
            .fillMaxHeight()
    } else {
        Modifier
            .fillMaxWidth()
            .height(11.dp)
    }

    Box(
        modifier = modifier
            .then(hitModifier)
            .hoverable(interactionSource)
            .pointerHoverIcon(cursor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        // Double-click resets divider to 50/50 balance
                        onRatioChangedUpdated(0.5f)
                    }
                )
            }
            .pointerInput(orientation) {
                var accumulatedRatio = currentSplitRatio
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        accumulatedRatio = currentSplitRatio
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val totalPx = currentTotalDimensionPx
                        if (totalPx > 0f) {
                            val deltaRatio = if (orientation == DockOrientation.HORIZONTAL) {
                                dragAmount.x / totalPx
                            } else {
                                dragAmount.y / totalPx
                            }
                            accumulatedRatio = (accumulatedRatio + deltaRatio).coerceIn(0.05f, 0.95f)
                            onRatioChangedUpdated(accumulatedRatio)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Center hairline divider line
        val isActive = isHovered || isDragging
        val lineColor = if (isActive) UmbrixTheme.colors.accentPrimary else UmbrixTheme.colors.border
        val lineThickness = if (isActive) 2.dp else 1.dp

        if (orientation == DockOrientation.HORIZONTAL) {
            Box(
                modifier = Modifier
                    .width(lineThickness)
                    .fillMaxHeight()
                    .background(lineColor)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(lineThickness)
                    .background(lineColor)
            )
        }

        // Optional Center Sync Scrolling Badge
        if (onToggleSyncScroll != null) {
            val badgeBg = if (isSyncScrollEnabled) {
                UmbrixTheme.colors.accentPrimary
            } else if (isActive) {
                UmbrixTheme.colors.surfaceElevated
            } else {
                UmbrixTheme.colors.surfacePanel
            }
            val badgeTextColor = if (isSyncScrollEnabled) {
                UmbrixTheme.colors.textPrimary
            } else {
                UmbrixTheme.colors.textMuted
            }

            Tooltip(
                tooltip = {
                    Text(
                        if (isSyncScrollEnabled) "Disable Synchronized Scrolling"
                        else "Enable Synchronized Scrolling (Correlate Viewports)"
                    )
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(badgeBg)
                        .clickable { onToggleSyncScroll() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⇋",
                        fontSize = 10.sp,
                        color = badgeTextColor
                    )
                }
            }
        }
    }
}
