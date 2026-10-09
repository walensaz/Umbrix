package umbrix.ui.dialog.settings

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import umbrix.ui.theme.UmbrixTheme

/**
 * Settings tab: Embedded MCP server configuration and in-app AI Forensic Assistant LLM setup.
 */
@Composable
fun AiMcpSettingsTab(
    isMcpServerEnabled: Boolean,
    onIsMcpServerEnabledChange: (Boolean) -> Unit,
    mcpServerPort: String,
    onMcpServerPortChange: (String) -> Unit,
    aiProvider: String,
    onAiProviderChange: (String) -> Unit,
    aiModelName: String,
    onAiModelNameChange: (String) -> Unit,
    aiEndpointUrl: String,
    onAiEndpointUrlChange: (String) -> Unit,
    aiApiKey: String,
    onAiApiKeyChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Embedded Model Context Protocol (MCP) Server",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = UmbrixTheme.colors.textPrimary
        )
        Text(
            text = "Allows external AI coding agents (Cursor, Claude Desktop, Antigravity, Copilot) to query active 25GB+ logs in sub-200ms without blowing their token limits.",
            fontSize = 12.sp,
            color = UmbrixTheme.colors.textMuted
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onIsMcpServerEnabledChange(!isMcpServerEnabled) }
        ) {
            Checkbox(
                checked = isMcpServerEnabled,
                onCheckedChange = onIsMcpServerEnabledChange
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Enable Embedded MCP HTTP Server",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = UmbrixTheme.colors.textPrimary
                )
                Text(
                    text = "Listens on local loopback (default port: 4242). External agents connect to http://127.0.0.1:4242/mcp",
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.textMuted
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Loopback Port: ",
                fontSize = 12.sp,
                color = UmbrixTheme.colors.textSecondary,
                modifier = Modifier.width(140.dp)
            )
            TextField(
                value = mcpServerPort,
                onValueChange = onMcpServerPortChange,
                modifier = Modifier.width(120.dp),
                enabled = isMcpServerEnabled
            )
        }

        Divider(Orientation.Horizontal)

        Text(
            text = "In-App AI Forensic Assistant LLM Connection",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = UmbrixTheme.colors.textPrimary
        )
        Text(
            text = "Configure the LLM engine for the in-app AI Forensic Assistant drawer (Ctrl+Shift+I). Works with local Ollama, LM Studio, or OpenAI-compatible cloud endpoints.",
            fontSize = 12.sp,
            color = UmbrixTheme.colors.textMuted
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "AI Engine Provider:",
                fontSize = 12.sp,
                color = UmbrixTheme.colors.textSecondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Ollama (Local)", "OpenAI Compatible", "Custom MCP").forEach { providerOption ->
                    val isSelected = aiProvider == providerOption
                    OutlinedButton(
                        onClick = {
                            onAiProviderChange(providerOption)
                            if (providerOption == "Ollama (Local)") {
                                onAiEndpointUrlChange("http://localhost:11434")
                                onAiModelNameChange("llama3.2")
                            } else if (providerOption == "OpenAI Compatible") {
                                onAiEndpointUrlChange("https://api.openai.com")
                                onAiModelNameChange("gpt-4o-mini")
                            }
                        }
                    ) {
                        Text(
                            text = providerOption,
                            color = if (isSelected) UmbrixTheme.colors.textPrimary else UmbrixTheme.colors.textMuted,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Endpoint URL:",
                fontSize = 12.sp,
                color = UmbrixTheme.colors.textSecondary
            )
            TextField(
                value = aiEndpointUrl,
                onValueChange = onAiEndpointUrlChange,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Model Name:",
                    fontSize = 12.sp,
                    color = UmbrixTheme.colors.textSecondary
                )
                TextField(
                    value = aiModelName,
                    onValueChange = onAiModelNameChange,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "API Key (Optional for Local Ollama):",
                    fontSize = 12.sp,
                    color = UmbrixTheme.colors.textSecondary
                )
                TextField(
                    value = aiApiKey,
                    onValueChange = onAiApiKeyChange,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(UmbrixTheme.colors.surfaceElevated, shape = RoundedCornerShape(6.dp))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "External Agent Connection Helper",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = UmbrixTheme.colors.textPrimary
                )
                Text(
                    text = "To connect Cursor, Antigravity, or Claude Desktop to Umbrix, add this to your MCP config:\n{\n  \"mcpServers\": {\n    \"umbrix\": { \"url\": \"http://127.0.0.1:${mcpServerPort.ifBlank { "4242" }}/mcp\" }\n  }\n}",
                    fontSize = 11.sp,
                    color = UmbrixTheme.colors.textMuted
                )
            }
        }
    }
}
