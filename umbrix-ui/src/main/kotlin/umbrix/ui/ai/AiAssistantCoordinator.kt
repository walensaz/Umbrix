package umbrix.ui.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import umbrix.core.session.LogSession
import umbrix.mcp.tools.ForensicToolRegistry
import umbrix.ui.settings.WorkstationSettings
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Message in the AI Assistant conversation.
 */
data class AiChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: AiSender,
    val text: String,
    val toolCallInfo: String? = null,
    val referencedLines: List<Long> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

enum class AiSender {
    USER, ASSISTANT, SYSTEM
}

/**
 * Autonomous AI client coordinator.
 * Executes natural language queries against local Ollama or OpenAI-compatible endpoints,
 * automatically augmenting context with local high-performance forensic tools (search_logs, get_time_slice, etc.).
 */
class AiAssistantCoordinator(
    private val toolRegistry: ForensicToolRegistry
) {
    private val logger = LoggerFactory.getLogger(AiAssistantCoordinator::class.java)
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    suspend fun processUserPrompt(
        prompt: String,
        settings: WorkstationSettings,
        session: LogSession?
    ): AiChatMessage = withContext(Dispatchers.IO) {
        if (session == null) {
            return@withContext AiChatMessage(
                sender = AiSender.ASSISTANT,
                text = "No active log file is currently open. Please open a log file first to start forensic investigation."
            )
        }

        // 1. Analyze prompt intent and run forensic tool automatically if applicable
        val lower = prompt.lowercase()
        val toolOutputSb = StringBuilder()
        val detectedLines = mutableListOf<Long>()

        if (lower.contains("cluster") || lower.contains("error pattern") || lower.contains("top error")) {
            val res = toolRegistry.executeTool("get_error_clusters", java.util.Map.of<String, String>())
            res.content().get().head().text()?.let {
                toolOutputSb.append("\n[Tool: get_error_clusters]\n").append(it).append("\n")
                extractLinesFromText(it, detectedLines)
            }
        } else if (lower.contains("causal") || lower.contains("context around") || lower.contains("line ")) {
            val lineRegex = Regex("""(?:line\s*#?|#)(\d+)""", RegexOption.IGNORE_CASE)
            val match = lineRegex.find(prompt)
            if (match != null) {
                val lineNum = match.groupValues[1]
                val args = java.util.Map.of(
                    "target_line", lineNum,
                    "backward_lines", "15",
                    "forward_lines", "10"
                )
                val res = toolRegistry.executeTool("extract_causal_context", args)
                res.content().get().head().text()?.let {
                    toolOutputSb.append("\n[Tool: extract_causal_context on Line $lineNum]\n").append(it).append("\n")
                    extractLinesFromText(it, detectedLines)
                }
            }
        } else {
            // General query - extract keywords or temporal indicators
            val timeRegex = Regex("""(\d{1,2}:\d{2}(?::\d{2})?)\s*(?:to|-|and)\s*(\d{1,2}:\d{2}(?::\d{2})?)""", RegexOption.IGNORE_CASE)
            val timeMatch = timeRegex.find(prompt)
            if (timeMatch != null) {
                val start = timeMatch.groupValues[1]
                val end = timeMatch.groupValues[2]
                val args = java.util.Map.of(
                    "start_time", start,
                    "end_time", end,
                    "limit", "40"
                )
                val res = toolRegistry.executeTool("get_time_slice", args)
                res.content().get().head().text()?.let {
                    toolOutputSb.append("\n[Tool: get_time_slice from $start to $end]\n").append(it).append("\n")
                    extractLinesFromText(it, detectedLines)
                }
            } else {
                // Keyword search
                val searchTerms = extractSearchQuery(prompt)
                val args = java.util.Map.of(
                    "query", searchTerms,
                    "max_results", "25"
                )
                val res = toolRegistry.executeTool("search_logs", args)
                res.content().get().head().text()?.let {
                    toolOutputSb.append("\n[Tool: search_logs for '$searchTerms']\n").append(it).append("\n")
                    extractLinesFromText(it, detectedLines)
                }
            }
        }

        // 2. Query LLM if configured, or provide synthesis directly from high-speed forensic tools
        val evidenceText = toolOutputSb.toString().trim()
        val synthesizedResponse = if (isLlmAvailable(settings)) {
            callLlm(prompt, evidenceText, settings, session)
        } else {
            // Direct offline high-precision workstation response
            buildDirectWorkstationResponse(prompt, evidenceText, detectedLines)
        }

        AiChatMessage(
            sender = AiSender.ASSISTANT,
            text = synthesizedResponse,
            toolCallInfo = if (evidenceText.isNotEmpty()) evidenceText.take(120) + "..." else null,
            referencedLines = detectedLines.distinct().take(15)
        )
    }

    private fun extractSearchQuery(prompt: String): String {
        val cleaned = prompt
            .replace(Regex("""(?i)\b(find|search|look for|any logging with|show me|where is|between|and)\b"""), " ")
            .replace(Regex("""['"]"""), "")
            .trim()
        return if (cleaned.isNotEmpty()) cleaned else "ERROR"
    }

    private fun extractLinesFromText(text: String, outList: MutableList<Long>) {
        val regex = Regex("""\[Line\s+(\d+)\]""")
        regex.findAll(text).forEach { m ->
            m.groupValues[1].toLongOrNull()?.let { outList.add(it) }
        }
    }

    private fun buildDirectWorkstationResponse(
        prompt: String,
        evidence: String,
        lines: List<Long>
    ): String {
        return buildString {
            if (evidence.isNotEmpty()) {
                appendLine("Here is what I found in the active log session:")
                appendLine()
                appendLine("```text")
                appendLine(evidence.take(4000))
                appendLine("```")
                if (lines.isNotEmpty()) {
                    appendLine()
                    appendLine("Click any referenced line below to jump immediately to that location in the viewport.")
                }
            } else {
                appendLine("No matching log records were found for your query. Try broadening your search or specifying a different timestamp window.")
            }
        }
    }

    private fun isLlmAvailable(settings: WorkstationSettings): Boolean {
        return settings.aiEndpointUrl.isNotBlank() && (settings.aiProvider.contains("Ollama") || settings.aiApiKey.isNotBlank())
    }

    private fun callLlm(
        userPrompt: String,
        evidence: String,
        settings: WorkstationSettings,
        session: LogSession
    ): String {
        try {
            val systemPrompt = "You are Umbrix AI, an expert high-performance log analysis assistant. Answer the engineer's question based strictly on the provided log evidence. Be concise, highlight root causes, and mention specific line numbers."
            val combinedUserContent = "User Query: $userPrompt\n\nLog Evidence from Workstation:\n$evidence"

            val isOllama = settings.aiProvider.contains("Ollama")
            val endpoint = if (isOllama) {
                "${settings.aiEndpointUrl.trimEnd('/')}/api/generate"
            } else {
                "${settings.aiEndpointUrl.trimEnd('/')}/v1/chat/completions"
            }

            val escapedModel = escapeJson(settings.aiModelName)
            val escapedSystemPrompt = escapeJson(systemPrompt)
            val escapedCombinedUserContent = escapeJson(combinedUserContent)

            val requestBody = if (isOllama) {
                val ollamaPrompt = escapeJson("$systemPrompt\n\n$combinedUserContent")
                """{"model":"$escapedModel","prompt":"$ollamaPrompt","stream":false}"""
            } else {
                """{"model":"$escapedModel","messages":[{"role":"system","content":"$escapedSystemPrompt"},{"role":"user","content":"$escapedCombinedUserContent"}]}"""
            }

            val reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))

            if (!isOllama && settings.aiApiKey.isNotBlank()) {
                reqBuilder.header("Authorization", "Bearer ${settings.aiApiKey}")
            }

            val response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                val respBody = response.body()
                // Simple fast parsing of 'response' for ollama or 'content' for openai
                val contentMatch = Regex(""""content"\s*:\s*"((?:\\.|[^"\\])*)"""").find(respBody)
                    ?: Regex(""""response"\s*:\s*"((?:\\.|[^"\\])*)"""").find(respBody)

                if (contentMatch != null) {
                    return unescapeJson(contentMatch.groupValues[1])
                }
            }
        } catch (ex: Exception) {
            logger.warn("LLM generation fallback: ${ex.message}")
        }
        return buildDirectWorkstationResponse(userPrompt, evidence, emptyList())
    }

    private fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length + 16)
        for (i in 0 until value.length) {
            val c = value[i]
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun unescapeJson(str: String): String {
        return str.replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }
}
