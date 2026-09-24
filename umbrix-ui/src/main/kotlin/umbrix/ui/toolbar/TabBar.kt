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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.state.WorkspaceTab
import umbrix.ui.theme.UmbrixTheme

/**
 * Clean IntelliJ-style Tab Bar for multi-document workstation workflows.
 */
@Composable
fun TabBar(
    tabs: List<WorkspaceTab>,
    activeTabIndex: Int,
    onSelectTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onNewTabClicked: () -> Unit
) {
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

            Row(
                modifier = Modifier
                    .background(tabBackground, shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onSelectTab(index) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tab.session.fileName(),
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
            Spacer(modifier = Modifier.width(2.dp))
        }

        // New Tab (+) Button
        Box(
            modifier = Modifier
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
}
