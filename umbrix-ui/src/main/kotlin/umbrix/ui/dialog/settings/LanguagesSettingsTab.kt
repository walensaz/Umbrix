package umbrix.ui.dialog.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.RadioButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.ui.theme.UmbrixTheme

/**
 * Settings tab: Log language profiles, exception parsing, and custom error/warn keywords.
 */
@Composable
fun LanguagesSettingsTab(
    activeLanguageProfileId: String,
    onActiveLanguageProfileIdChange: (String) -> Unit,
    customErrorKeywords: String,
    onCustomErrorKeywordsChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "Log Language Profiles & Exception Formatting",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = UmbrixTheme.colors.textPrimary
        )
        Text(
            text = "Configures multi-line stack trace folding and error severity keywords.",
            fontSize = 12.sp,
            color = UmbrixTheme.colors.textMuted
        )

        val profiles = listOf(
            "auto" to "Auto-Detect Format (Recommended)",
            "java" to "Java / JVM (Logback, Log4j2, Spring Boot)",
            "python" to "Python (traceback, logging)",
            "go" to "Go (slog, zap, panic)",
            "rust" to "Rust (tracing, env_logger)",
            "syslog" to "Syslog & Generic Plaintext"
        )

        for ((id, label) in profiles) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onActiveLanguageProfileIdChange(id) }
            ) {
                RadioButton(
                    selected = activeLanguageProfileId == id,
                    onClick = { onActiveLanguageProfileIdChange(id) }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(label, fontSize = 13.sp)
            }
        }

        Divider(Orientation.Horizontal)

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Custom Error Keywords (comma separated):", fontSize = 12.sp, color = UmbrixTheme.colors.textSecondary)
            TextField(
                value = customErrorKeywords,
                onValueChange = onCustomErrorKeywordsChange,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
