package umbrix.ui.toast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.theme.UmbrixTheme

/**
 * Overlay host rendering non-intrusive floating toast notifications above the workstation viewport.
 */
@Composable
fun ToastHost(
    modifier: Modifier = Modifier
) {
    val toasts = ToastManager.activeToasts

    Box(
        modifier = modifier.padding(bottom = 36.dp, end = 20.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            for (toast in toasts) {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 }
                ) {
                    ToastCard(
                        toast = toast,
                        onDismiss = { ToastManager.dismiss(toast.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ToastCard(
    toast: ToastMessage,
    onDismiss: () -> Unit
) {
    val colors = UmbrixTheme.colors

    val (bgTint, borderTint, iconGlyph, iconColor) = when (toast.type) {
        ToastType.INFO -> {
            val bg = if (colors.isDark) Color(0xFF1E2228) else Color(0xFFF0F4F8)
            val border = if (colors.isDark) Color(0xFF2E3846) else Color(0xFFB0BEC5)
            val iconCol = if (colors.isDark) Color(0xFF4FC3F7) else Color(0xFF0288D1)
            Quad(bg, border, "ℹ", iconCol)
        }
        ToastType.SUCCESS -> {
            val bg = if (colors.isDark) Color(0xFF142E1B) else Color(0xFFE8F5E9)
            val border = if (colors.isDark) Color(0xFF2E7D32) else Color(0xFF81C784)
            val iconCol = if (colors.isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
            Quad(bg, border, "✓", iconCol)
        }
        ToastType.WARNING -> {
            val bg = if (colors.isDark) Color(0xFF332509) else Color(0xFFFFF8E1)
            val border = if (colors.isDark) Color(0xFFFFB300) else Color(0xFFFFD54F)
            val iconCol = if (colors.isDark) Color(0xFFFFD54F) else Color(0xFFF57F17)
            Quad(bg, border, "⚠", iconCol)
        }
        ToastType.ERROR -> {
            val bg = if (colors.isDark) Color(0xFF381515) else Color(0xFFFFEBEE)
            val border = if (colors.isDark) Color(0xFFE57373) else Color(0xFFEF9A9A)
            val iconCol = if (colors.isDark) Color(0xFFEF5350) else Color(0xFFC62828)
            Quad(bg, border, "✕", iconCol)
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(min = 220.dp, max = 460.dp)
            .background(bgTint, shape = RoundedCornerShape(8.dp))
            .border(1.dp, borderTint, shape = RoundedCornerShape(8.dp))
            .clickable { onDismiss() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = iconGlyph,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = iconColor
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = toast.message,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "✕",
            fontSize = 10.sp,
            color = colors.textMuted,
            modifier = Modifier
                .clickable { onDismiss() }
                .padding(2.dp)
        )
    }
}

private data class Quad<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
