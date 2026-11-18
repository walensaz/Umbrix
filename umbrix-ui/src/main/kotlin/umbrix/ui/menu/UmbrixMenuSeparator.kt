package umbrix.ui.menu

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import umbrix.ui.theme.UmbrixTheme

/**
 * Subtle 1.dp hairline separating logical clusters of menu actions (12.dp horizontal inset).
 */
@Composable
fun UmbrixMenuSeparator(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(1.dp)
            .background(UmbrixTheme.colors.menuSeparator)
    )
}
