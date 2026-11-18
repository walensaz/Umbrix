package umbrix.ui.docking

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.theme.UmbrixTheme

/**
 * Animated visual drop indicator overlay rendering a translucent preview outline
 * with border highlights and a central badge over the target docking zone,
 * or an insertion caret bar when hovering over a tab strip.
 */
@Composable
fun DockGhostOverlay(
    modifier: Modifier = Modifier
) {
    if (!DockDragCoordinator.isDragging) return

    val dropTarget = DockDragCoordinator.activeDropTarget
    val density = LocalDensity.current
    var overlayOffset by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                overlayOffset = coordinates.positionInWindow()
            }
    ) {
        if (dropTarget != null && dropTarget.isTabBarDrop && dropTarget.tabInsertionX != null) {
            // Tab Strip Insertion Marker Line: aligned vertically inside the tab bar next to the tab
            val barRect = dropTarget.targetRect
            val localInsertX = dropTarget.tabInsertionX - overlayOffset.x
            val localBarTop = barRect.top - overlayOffset.y

            val insertXDp = with(density) { localInsertX.toDp() }
            val barTopDp = with(density) { localBarTop.toDp() } + 2.dp
            val barHeightDp = (with(density) { barRect.height.toDp() } - 4.dp).coerceAtLeast(16.dp)

            Box(
                modifier = Modifier
                    .offset(x = insertXDp - 1.5.dp, y = barTopDp)
                    .size(width = 3.dp, height = barHeightDp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(UmbrixTheme.colors.accentPrimary)
                    .shadow(4.dp, RoundedCornerShape(1.5.dp))
            )
        } else if (dropTarget != null) {
            val r = dropTarget.targetRect
            val localLeft = r.left - overlayOffset.x
            val localTop = r.top - overlayOffset.y

            val animLeft by animateFloatAsState(localLeft, animationSpec = tween(90))
            val animTop by animateFloatAsState(localTop, animationSpec = tween(90))
            val animWidth by animateFloatAsState(r.width, animationSpec = tween(90))
            val animHeight by animateFloatAsState(r.height, animationSpec = tween(90))

            val leftDp = with(density) { animLeft.toDp() } + 2.dp
            val topDp = with(density) { animTop.toDp() } + 2.dp
            val widthDp = (with(density) { animWidth.toDp() } - 4.dp).coerceAtLeast(40.dp)
            val heightDp = (with(density) { animHeight.toDp() } - 4.dp).coerceAtLeast(40.dp)

            // Ghost Outline Box for Canvas Splits / Drops
            Box(
                modifier = Modifier
                    .offset(x = leftDp, y = topDp)
                    .size(width = widthDp, height = heightDp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(UmbrixTheme.colors.accentPrimary.copy(alpha = 0.16f))
                    .border(2.dp, UmbrixTheme.colors.accentPrimary, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Central action badge chip
                Row(
                    modifier = Modifier
                        .shadow(8.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(UmbrixTheme.colors.surfaceElevated)
                        .border(1.dp, UmbrixTheme.colors.border, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dropTarget.dropZone.glyph,
                        fontSize = 14.sp,
                        color = UmbrixTheme.colors.accentPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = dropTarget.dropZone.displayLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = UmbrixTheme.colors.textPrimary
                    )
                }
            }
        }

        // Floating tab chip following the cursor
        val pointer = DockDragCoordinator.pointerWindowOffset
        val localPointerX = pointer.x - overlayOffset.x
        val localPointerY = pointer.y - overlayOffset.y
        val cursorLeftDp = with(density) { (localPointerX + 14f).toDp() }
        val cursorTopDp = with(density) { (localPointerY + 14f).toDp() }

        Row(
            modifier = Modifier
                .offset(x = cursorLeftDp, y = cursorTopDp)
                .shadow(12.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp))
                .background(UmbrixTheme.colors.surfaceElevated)
                .border(1.dp, UmbrixTheme.colors.border, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📄", fontSize = 12.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = DockDragCoordinator.draggedTabTitle ?: "Tab",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = UmbrixTheme.colors.textPrimary
            )
        }
    }
}
