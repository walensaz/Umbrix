package umbrix.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.settings.CommandPaletteStyle
import umbrix.ui.theme.UmbrixTheme

/**
 * Header search bar for the Command Palette dialog.
 */
@Composable
internal fun CommandPaletteSearchHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    paletteStyle: CommandPaletteStyle,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(UmbrixTheme.colors.surfacePanel)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val iconGlyph = when {
            query.trim().startsWith(":") -> ":"
            query.trim().startsWith(">") -> ">"
            else -> "🔍"
        }

        Text(
            text = iconGlyph,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = UmbrixTheme.colors.accentPrimary
        )

        Spacer(modifier = Modifier.width(12.dp))

        val placeholderText = when (paletteStyle) {
            CommandPaletteStyle.VSCODE -> "Type a command, 'p' for files, or ':' for line jump..."
            CommandPaletteStyle.INTELLIJ -> "Search everywhere (actions, files, settings)..."
            CommandPaletteStyle.VIM -> "Enter Vim command (:w, :q, :line, :set, :theme) or search..."
            CommandPaletteStyle.OMNI -> "Type a command ('>'), Vim (':'), line ('#'), or search..."
        }

        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = placeholderText,
                    fontSize = 13.sp,
                    color = UmbrixTheme.colors.textSubtle
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                textStyle = TextStyle(
                    color = UmbrixTheme.colors.textPrimary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                ),
                cursorBrush = SolidColor(UmbrixTheme.colors.accentPrimary)
            )
        }

        if (query.isNotEmpty()) {
            Text(
                text = "✕",
                fontSize = 12.sp,
                color = UmbrixTheme.colors.textSubtle,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onQueryChange("") }
                    .padding(4.dp)
            )
        }
    }
}

/**
 * Filter pills for selecting command category in IntelliJ or Omni mode.
 */
@Composable
internal fun CommandPaletteCategoryBar(
    categories: List<CommandCategory?>,
    activeCategory: CommandCategory?,
    onSelectCategory: (CommandCategory?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(UmbrixTheme.colors.surfacePanel.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        categories.forEach { cat ->
            val isSelected = activeCategory == cat
            val label = cat?.label ?: "All"
            val bg = if (isSelected) UmbrixTheme.colors.accentPrimary else UmbrixTheme.colors.surfaceElevated
            val fg = if (isSelected) Color.White else UmbrixTheme.colors.textSecondary

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(bg)
                    .clickable { onSelectCategory(cat) }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = fg
                )
            }
        }
    }
}

/**
 * Status banner showing parsed Vim action or syntax error.
 */
@Composable
internal fun CommandPaletteVimBanner(
    vimResult: VimCommandResult,
    modifier: Modifier = Modifier
) {
    when (vimResult) {
        is VimCommandResult.Execute -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(UmbrixTheme.colors.accentPrimary.copy(alpha = 0.12f))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "⚡", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = vimResult.description,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = UmbrixTheme.colors.accentPrimary
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Press Enter to run",
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.textSubtle
                )
            }
        }
        is VimCommandResult.Error -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .background(UmbrixTheme.colors.severityError.copy(alpha = 0.1f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "⚠", fontSize = 14.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = vimResult.message,
                    fontSize = 12.sp,
                    color = UmbrixTheme.colors.severityError
                )
            }
        }
        VimCommandResult.NotVimCommand -> Unit
    }
}

/**
 * Individual command result item in the filtered list.
 */
@Composable
internal fun CommandPaletteResultRow(
    cmd: PaletteCommand,
    isSelected: Boolean,
    showShortcuts: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rowBg = if (isSelected) {
        UmbrixTheme.colors.accentPrimary.copy(alpha = 0.18f)
    } else {
        UmbrixTheme.colors.surfaceElevated
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = cmd.category.iconGlyph,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = cmd.title,
            fontSize = 12.5.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) UmbrixTheme.colors.accentPrimary else UmbrixTheme.colors.textPrimary,
            modifier = Modifier.weight(1f)
        )

        if (showShortcuts && !cmd.shortcutHint.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(UmbrixTheme.colors.surfacePanel)
                    .border(1.dp, UmbrixTheme.colors.border, RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = cmd.shortcutHint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = UmbrixTheme.colors.textSecondary
                )
            }
        }
    }
}

/**
 * Placeholder displayed when no commands match query.
 */
@Composable
internal fun CommandPaletteEmptyState(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "No matching commands or navigation targets found",
            fontSize = 12.sp,
            color = UmbrixTheme.colors.textSubtle
        )
    }
}

/**
 * Footer displaying total result count and keyboard shortcut guide.
 */
@Composable
internal fun CommandPaletteFooter(
    resultCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(UmbrixTheme.colors.surfacePanel)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$resultCount items",
            fontSize = 11.sp,
            color = UmbrixTheme.colors.textSubtle
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "↑↓ Navigate",
                fontSize = 10.sp,
                color = UmbrixTheme.colors.textSubtle
            )
            Text(
                text = "↵ Select",
                fontSize = 10.sp,
                color = UmbrixTheme.colors.textSubtle
            )
            Text(
                text = "Tab Category",
                fontSize = 10.sp,
                color = UmbrixTheme.colors.textSubtle
            )
            Text(
                text = "Esc Exit",
                fontSize = 10.sp,
                color = UmbrixTheme.colors.textSubtle
            )
        }
    }
}
