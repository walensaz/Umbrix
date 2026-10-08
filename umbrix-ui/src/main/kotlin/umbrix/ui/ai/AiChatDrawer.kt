package umbrix.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import umbrix.core.session.LogSession
import umbrix.ui.settings.WorkstationSettings
import umbrix.ui.theme.UmbrixTheme

/**
 * Slide-out AI Assistant Chat Drawer.
 * Allows interactive querying of the open log session with auto tool calling and line navigation.
 */
@Composable
fun AiChatDrawer(
    isOpen: Boolean,
    onClose: () -> Unit,
    coordinator: AiAssistantCoordinator,
    session: LogSession?,
    settings: WorkstationSettings,
    onJumpToLine: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isOpen) return

    val colors = UmbrixTheme.colors
    val coroutineScope = rememberCoroutineScope()

    var messages by remember {
        mutableStateOf(
            listOf(
                AiChatMessage(
                    sender = AiSender.ASSISTANT,
                    text = "Hello! I am your Umbrix AI Assistant. I have hardware-accelerated access to your active log session.\n\nAsk me anything, like:\n• \"Between 12:00 and 12:05 find any logging with NullPointerException\"\n• \"What are the top error clusters?\"\n• \"Extract causal context around line #1200\""
                )
            )
        )
    }

    var inputText by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(420.dp)
            .border(1.dp, colors.border),
        color = colors.surface,
        elevation = 8.dp
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(colors.surfaceElevated)
                    .border(1.dp, colors.border)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "⚡",
                        fontSize = 16.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = "Umbrix AI Forensic Assistant",
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "✕",
                    color = colors.textMuted,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clickable { onClose() }
                        .padding(4.dp)
                )
            }

            // Quick Prompt Suggestions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QuickChip("Cluster Errors", colors) {
                    inputText = "What are the top error clusters?"
                }
                QuickChip("Find NPEs", colors) {
                    inputText = "Find any logging with NullPointerException"
                }
                QuickChip("Time Window", colors) {
                    inputText = "Between 12:00 and 12:05 show all logs"
                }
            }

            Divider(color = colors.border, thickness = 1.dp)

            // Message List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatMessageBubble(msg, colors, onJumpToLine)
                }

                if (isThinking) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = colors.accentPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Running hardware forensic tools & analyzing...",
                                color = colors.textMuted,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            // Input Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.border),
                color = colors.surfaceElevated
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("Ask about active logs...", fontSize = 12.sp, color = colors.textMuted) },
                        modifier = Modifier
                            .weight(1f)
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter && !keyEvent.isShiftPressed) {
                                    if (inputText.isNotBlank() && !isThinking) {
                                        val prompt = inputText.trim()
                                        inputText = ""
                                        messages = messages + AiChatMessage(sender = AiSender.USER, text = prompt)
                                        isThinking = true

                                        coroutineScope.launch {
                                            val response = coordinator.processUserPrompt(prompt, settings, session)
                                            messages = messages + response
                                            isThinking = false
                                        }
                                    }
                                    true
                                } else {
                                    false
                                }
                            },
                        colors = TextFieldDefaults.textFieldColors(
                            textColor = colors.textPrimary,
                            backgroundColor = colors.surface,
                            cursorColor = colors.accentPrimary,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(6.dp),
                        maxLines = 3
                    )

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = {
                            if (inputText.isNotBlank() && !isThinking) {
                                val prompt = inputText.trim()
                                inputText = ""
                                messages = messages + AiChatMessage(sender = AiSender.USER, text = prompt)
                                isThinking = true

                                coroutineScope.launch {
                                    val response = coordinator.processUserPrompt(prompt, settings, session)
                                    messages = messages + response
                                    isThinking = false
                                }
                            }
                        },
                        enabled = inputText.isNotBlank() && !isThinking,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = colors.accentPrimary,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text("Send", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickChip(
    label: String,
    colors: umbrix.ui.theme.UmbrixThemeSpec,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceElevated)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = colors.textPrimary
        )
    }
}

@Composable
private fun ChatMessageBubble(
    msg: AiChatMessage,
    colors: umbrix.ui.theme.UmbrixThemeSpec,
    onJumpToLine: (Long) -> Unit
) {
    val isUser = msg.sender == AiSender.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isUser) colors.accentPrimary.copy(alpha = 0.25f) else colors.surfaceElevated)
                .border(1.dp, if (isUser) colors.accentPrimary.copy(alpha = 0.5f) else colors.border, RoundedCornerShape(8.dp))
                .padding(10.dp)
                .widthIn(max = 380.dp)
        ) {
            SelectionContainer {
                Column {
                    Text(
                        text = msg.text,
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    if (msg.referencedLines.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Referenced Lines (click to jump):",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = colors.textMuted
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            msg.referencedLines.forEach { lineNum ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(colors.accentPrimary.copy(alpha = 0.2f))
                                        .border(1.dp, colors.accentPrimary, RoundedCornerShape(4.dp))
                                        .clickable { onJumpToLine(lineNum) }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "#$lineNum",
                                        color = colors.accentPrimary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
