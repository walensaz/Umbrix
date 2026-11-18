package umbrix.ui.docking

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import umbrix.ui.state.WorkspaceTab

/**
 * Recursive layout host rendering either a split container (with [DockSplitDivider])
 * or a terminal [DockPaneView].
 */
@Composable
fun DockLayoutHost(
    node: DockNode,
    openTabs: List<WorkspaceTab>,
    activeLeafId: String,
    totalLeavesCount: Int,
    callbacks: DockViewportCallbacks,
    modifier: Modifier = Modifier
) {
    when (node) {
        is DockNode.Leaf -> {
            DockPaneView(
                leaf = node,
                openTabs = openTabs,
                isActiveLeaf = node.id == activeLeafId,
                totalLeavesCount = totalLeavesCount,
                callbacks = callbacks,
                modifier = modifier
            )
        }

        is DockNode.Split -> {
            // Synchronized Scrolling Coordination between first and second children
            if (node.isSyncScrollEnabled) {
                val leafA = DockTreeManager.allLeaves(node.firstChild).firstOrNull()
                val leafB = DockTreeManager.allLeaves(node.secondChild).firstOrNull()
                val tabA = openTabs.find { it.id == leafA?.activeTabId }
                val tabB = openTabs.find { it.id == leafB?.activeTabId }

                if (tabA?.session != null && tabB?.session != null) {
                    var lastLineA by remember(tabA.id) { mutableStateOf(tabA.topVisibleLine) }
                    LaunchedEffect(tabA.topVisibleLine) {
                        val delta = tabA.topVisibleLine - lastLineA
                        if (delta != 0L) {
                            lastLineA = tabA.topVisibleLine
                            val totalB = tabB.session.totalLines()
                            tabB.topVisibleLine = (tabB.topVisibleLine + delta).coerceIn(0L, maxOf(0L, totalB - 1L))
                        }
                    }
                }
            }

            BoxWithConstraints(modifier = modifier.fillMaxSize()) {
                val totalWidthPx = constraints.maxWidth.toFloat()
                val totalHeightPx = constraints.maxHeight.toFloat()
                val minPaneSizePx = with(LocalDensity.current) { 220.dp.toPx() }

                if (node.orientation == DockOrientation.HORIZONTAL) {
                    val minFraction = if (totalWidthPx > minPaneSizePx * 2.2f && minPaneSizePx > 0f) {
                        (minPaneSizePx / totalWidthPx).coerceIn(0.15f, 0.45f)
                    } else {
                        0.18f
                    }
                    val clampedRatio = node.splitRatio.coerceIn(minFraction, 1f - minFraction)

                    Row(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(clampedRatio)
                                .fillMaxHeight()
                        ) {
                            DockLayoutHost(
                                node = node.firstChild,
                                openTabs = openTabs,
                                activeLeafId = activeLeafId,
                                totalLeavesCount = totalLeavesCount,
                                callbacks = callbacks
                            )
                        }

                        DockSplitDivider(
                            orientation = DockOrientation.HORIZONTAL,
                            splitRatio = clampedRatio,
                            totalDimensionPx = totalWidthPx,
                            onSplitRatioChanged = { node.splitRatio = it },
                            isSyncScrollEnabled = node.isSyncScrollEnabled,
                            onToggleSyncScroll = { node.isSyncScrollEnabled = !node.isSyncScrollEnabled },
                            minPaneSizePx = minPaneSizePx
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f - clampedRatio)
                                .fillMaxHeight()
                        ) {
                            DockLayoutHost(
                                node = node.secondChild,
                                openTabs = openTabs,
                                activeLeafId = activeLeafId,
                                totalLeavesCount = totalLeavesCount,
                                callbacks = callbacks
                            )
                        }
                    }
                } else {
                    val minFraction = if (totalHeightPx > minPaneSizePx * 2.2f && minPaneSizePx > 0f) {
                        (minPaneSizePx / totalHeightPx).coerceIn(0.15f, 0.45f)
                    } else {
                        0.18f
                    }
                    val clampedRatio = node.splitRatio.coerceIn(minFraction, 1f - minFraction)

                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .weight(clampedRatio)
                                .fillMaxWidth()
                        ) {
                            DockLayoutHost(
                                node = node.firstChild,
                                openTabs = openTabs,
                                activeLeafId = activeLeafId,
                                totalLeavesCount = totalLeavesCount,
                                callbacks = callbacks
                            )
                        }

                        DockSplitDivider(
                            orientation = DockOrientation.VERTICAL,
                            splitRatio = clampedRatio,
                            totalDimensionPx = totalHeightPx,
                            onSplitRatioChanged = { node.splitRatio = it },
                            isSyncScrollEnabled = node.isSyncScrollEnabled,
                            onToggleSyncScroll = { node.isSyncScrollEnabled = !node.isSyncScrollEnabled },
                            minPaneSizePx = minPaneSizePx
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f - clampedRatio)
                                .fillMaxWidth()
                        ) {
                            DockLayoutHost(
                                node = node.secondChild,
                                openTabs = openTabs,
                                activeLeafId = activeLeafId,
                                totalLeavesCount = totalLeavesCount,
                                callbacks = callbacks
                            )
                        }
                    }
                }
            }
        }
    }
}
