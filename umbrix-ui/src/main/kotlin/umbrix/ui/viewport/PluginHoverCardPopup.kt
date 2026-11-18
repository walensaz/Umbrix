package umbrix.ui.viewport

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.jetbrains.jewel.ui.component.Text
import umbrix.plugin.api.decorator.HoverCardDecoration
import umbrix.ui.theme.UmbrixTheme

/**
 * Sleek Dark Carbon floating micro-card popup displaying rich contextual hover metadata
 * resolved from active `DocumentDecorator` extensions.
 */
@Composable
fun PluginHoverCardPopup(
    decoration: HoverCardDecoration,
    anchorOffsetPx: Offset,
    onDismissRequest: () -> Unit
) {
    val colors = UmbrixTheme.colors
    Popup(
        offset = IntOffset(anchorOffsetPx.x.toInt() + 12, anchorOffsetPx.y.toInt() + 20),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = false)
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 220.dp, max = 450.dp)
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .shadow(12.dp, RoundedCornerShape(8.dp))
                .background(colors.surfaceElevated, RoundedCornerShape(8.dp))
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = decoration.title(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (decoration.subtitle().isNotEmpty()) {
                Text(
                    text = decoration.subtitle(),
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Key-Value Pairs
            val kvPairs = decoration.keyValuePairs()
            if (kvPairs != null && kvPairs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (pair in kvPairs) {
                        val key = pair._1()
                        val value = pair._2()
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$key:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textMuted,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = value,
                                fontSize = 11.sp,
                                color = colors.textPrimary,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }
                }
            }

            // Monospace payload
            if (decoration.monospacePayload().isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = decoration.monospacePayload(),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.textSecondary,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(colors.surfaceGutter, RoundedCornerShape(4.dp))
                        .padding(6.dp)
                )
            }
        }
    }
}
