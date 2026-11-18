package umbrix.ui.dialog.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.RadioButton
import org.jetbrains.jewel.ui.component.Slider
import org.jetbrains.jewel.ui.component.Text
import umbrix.ui.theme.EditorTypography
import umbrix.ui.theme.FontSpec
import umbrix.ui.theme.UmbrixTheme

/**
 * Settings tab: Typography, font families, sizes, ligatures, and live preview.
 */
@Composable
fun FontsSettingsTab(
    editorFontFamily: String,
    onEditorFontFamilyChange: (String) -> Unit,
    editorFontSizePt: Float,
    onEditorFontSizePtChange: (Float) -> Unit,
    lineHeightMultiplier: Float,
    onLineHeightMultiplierChange: (Float) -> Unit,
    editorFontWeight: Int,
    onEditorFontWeightChange: (Int) -> Unit,
    uiFontFamily: String,
    onUiFontFamilyChange: (String) -> Unit,
    uiFontSizePt: Float,
    onUiFontSizePtChange: (Float) -> Unit,
    enableLigatures: Boolean,
    onEnableLigaturesChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Editor Typography",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = UmbrixTheme.colors.textPrimary
        )

        // 1. Editor Font
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Editor Font Family",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = UmbrixTheme.colors.textSecondary
            )
            Dropdown(
                modifier = Modifier.width(300.dp),
                menuContent = {
                    for (font in EditorTypography.availableSystemMonospaceFonts) {
                        selectableItem(
                            selected = (editorFontFamily.equals(font, ignoreCase = true)),
                            onClick = { onEditorFontFamilyChange(font) }
                        ) {
                            Text(font)
                        }
                    }
                }
            ) {
                Text(editorFontFamily)
            }
        }

        // 2. Font Size
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Font Size",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = UmbrixTheme.colors.textSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        onEditorFontSizePtChange((editorFontSizePt - 1.0f).coerceIn(FontSpec.MIN_FONT_SIZE, FontSpec.MAX_FONT_SIZE))
                    },
                    enabled = editorFontSizePt > FontSpec.MIN_FONT_SIZE
                ) {
                    Text("-", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "${String.format("%.1f", editorFontSizePt)} pt",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = UmbrixTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                OutlinedButton(
                    onClick = {
                        onEditorFontSizePtChange((editorFontSizePt + 1.0f).coerceIn(FontSpec.MIN_FONT_SIZE, FontSpec.MAX_FONT_SIZE))
                    },
                    enabled = editorFontSizePt < FontSpec.MAX_FONT_SIZE
                ) {
                    Text("+", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "(${FontSpec.MIN_FONT_SIZE.toInt()} pt – ${FontSpec.MAX_FONT_SIZE.toInt()} pt)",
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.textMuted
                )
            }
        }

        // 3. Line Height
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Line Height Multiplier",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = UmbrixTheme.colors.textSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = lineHeightMultiplier,
                    onValueChange = { newVal ->
                        onLineHeightMultiplierChange((Math.round(newVal * 20.0f) / 20.0f).coerceIn(FontSpec.MIN_LINE_HEIGHT_MULT, FontSpec.MAX_LINE_HEIGHT_MULT))
                    },
                    valueRange = FontSpec.MIN_LINE_HEIGHT_MULT..FontSpec.MAX_LINE_HEIGHT_MULT,
                    modifier = Modifier.width(220.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${String.format("%.2f", lineHeightMultiplier)}×",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = UmbrixTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "(${FontSpec.MIN_LINE_HEIGHT_MULT}× – ${FontSpec.MAX_LINE_HEIGHT_MULT}×)",
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.textMuted
                )
            }
        }

        // 4. Font Weight
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Font Weight",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = UmbrixTheme.colors.textSecondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    300 to "Light (300)",
                    400 to "Regular (400)",
                    500 to "Medium (500)",
                    700 to "Bold (700)"
                ).forEach { (weightVal, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onEditorFontWeightChange(weightVal) }
                    ) {
                        RadioButton(
                            selected = (editorFontWeight == weightVal),
                            onClick = { onEditorFontWeightChange(weightVal) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            color = if (editorFontWeight == weightVal) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textSecondary
                        )
                    }
                }
            }
        }

        // 5. Ligatures
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = enableLigatures,
                    onCheckedChange = onEnableLigaturesChange
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Enable OpenType ligatures (!=, ->, ==, >=)",
                    fontSize = 12.sp,
                    color = UmbrixTheme.colors.textPrimary
                )
            }
        }

        Divider(Orientation.Horizontal)

        // 6. UI Chrome Typography
        Text(
            text = "UI Chrome Typography",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = UmbrixTheme.colors.textPrimary
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "UI Font Family",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = UmbrixTheme.colors.textSecondary
            )
            Dropdown(
                modifier = Modifier.width(300.dp),
                menuContent = {
                    for (font in EditorTypography.availableSystemProportionalFonts) {
                        selectableItem(
                            selected = (uiFontFamily.equals(font, ignoreCase = true)),
                            onClick = { onUiFontFamilyChange(font) }
                        ) {
                            Text(font)
                        }
                    }
                }
            ) {
                Text(uiFontFamily)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "UI Font Size",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = UmbrixTheme.colors.textSecondary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        onUiFontSizePtChange((uiFontSizePt - 1.0f).coerceIn(FontSpec.MIN_UI_FONT_SIZE, FontSpec.MAX_UI_FONT_SIZE))
                    },
                    enabled = uiFontSizePt > FontSpec.MIN_UI_FONT_SIZE
                ) {
                    Text("-", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "${String.format("%.1f", uiFontSizePt)} pt",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = UmbrixTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                OutlinedButton(
                    onClick = {
                        onUiFontSizePtChange((uiFontSizePt + 1.0f).coerceIn(FontSpec.MIN_UI_FONT_SIZE, FontSpec.MAX_UI_FONT_SIZE))
                    },
                    enabled = uiFontSizePt < FontSpec.MAX_UI_FONT_SIZE
                ) {
                    Text("+", fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "(${FontSpec.MIN_UI_FONT_SIZE.toInt()} pt – ${FontSpec.MAX_UI_FONT_SIZE.toInt()} pt)",
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.textMuted
                )
            }
        }

        Divider(Orientation.Horizontal)

        // 7. Live Preview
        Text(
            text = "Live Preview",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = UmbrixTheme.colors.textPrimary
        )

        val previewFontFamily = remember(editorFontFamily) {
            EditorTypography.resolvedEditorFontFamily(editorFontFamily)
        }
        val previewFontWeight = remember(editorFontWeight) {
            FontSpec(editorFontWeight = editorFontWeight).resolvedEditorWeight
        }
        val previewFeatureSettings = remember(enableLigatures) {
            EditorTypography.resolveFontFeatureSettings(enableLigatures)
        }
        val previewSample = "2026-09-23 14:32:01.123 [main] INFO  com.app.Service - Starting application\n" +
            "2026-09-23 14:32:01.456 [pool-3] ERROR com.app.Handler - NullPointerException\n" +
            "    at com.app.Handler.process(Handler.java:42)\n" +
            "2026-09-23 14:32:02.789 [worker-1] WARN  com.app.Queue - queue.size() >= 5000 -> latency != 0ms"

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(UmbrixTheme.colors.surfaceGutter, shape = RoundedCornerShape(6.dp))
                .border(1.dp, UmbrixTheme.colors.border, shape = RoundedCornerShape(6.dp))
                .padding(12.dp)
        ) {
            Text(
                text = previewSample,
                fontFamily = previewFontFamily,
                fontSize = editorFontSizePt.sp,
                fontWeight = previewFontWeight,
                style = TextStyle(
                    fontFeatureSettings = previewFeatureSettings,
                    lineHeight = (editorFontSizePt * lineHeightMultiplier).sp
                ),
                color = UmbrixTheme.colors.textEditor
            )
        }
    }
}
