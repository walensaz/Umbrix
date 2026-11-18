package umbrix.mcp

import java.nio.file.Files
import munit.FunSuite
import com.github.plokhotnyuk.jsoniter_scala.core.*
import umbrix.core.session.LogSession
import umbrix.mcp.dlp.DlpSanitizer
import umbrix.mcp.protocol.*
import umbrix.mcp.protocol.McpCodecs.given
import umbrix.mcp.server.McpDispatcher
import umbrix.mcp.tools.ForensicToolRegistry

class McpServerTest extends FunSuite {

  test("DlpSanitizer redacts passwords, tokens, and AWS keys") {
    val raw = "User auth failed: password=secret123, auth: Bearer eyJhbGciOi.eyJzdWIi.signature and key AKIAIOSFODNN7EXAMPLE"
    val sanitized = DlpSanitizer.sanitize(raw)
    assert(!sanitized.contains("secret123"))
    assert(sanitized.contains("[REDACTED]"))
    assert(sanitized.contains("Bearer [REDACTED_JWT]"))
    assert(sanitized.contains("[REDACTED_AWS_KEY]"))
  }

  test("DlpSanitizer redacts high-entropy secret tokens while preserving paths and timestamps") {
    val secretToken = "aB9#zK2$mP5!vX8*qR1@yT4^"
    assert(DlpSanitizer.shannonEntropy(secretToken) >= 4.2)

    val raw = s"Session created at 2026-10-04T12:00:01.000Z for file /var/log/nginx/access.log with unlabelled key $secretToken"
    val sanitized = DlpSanitizer.sanitize(raw)
    assert(!sanitized.contains(secretToken))
    assert(sanitized.contains("[REDACTED_HIGH_ENTROPY]"))
    assert(sanitized.contains("2026-10-04T12:00:01.000Z"))
    assert(sanitized.contains("/var/log/nginx/access.log"))
  }

  test("McpServer token counter accurately tokenizes sample text") {
    val tokens = McpServer.countPromptTokens("Umbrix log workstation initialized.")
    assert(tokens > 0)
  }

  test("jsoniter-scala serializes and deserializes JSON-RPC MCP payload") {
    val request = JsonRpcRequest(jsonrpc = "2.0", id = Some(1L), method = "search_logs")
    val bytes = writeToArray(request)
    val parsed = readFromArray[JsonRpcRequest](bytes)
    assertEquals(parsed.jsonrpc, "2.0")
    assertEquals(parsed.id, Some(1L))
    assertEquals(parsed.method, "search_logs")
  }

  test("ForensicToolRegistry list tools and execute without session returns graceful error") {
    val registry = new ForensicToolRegistry(() => None)
    assertEquals(registry.definitions.size, 5)

    val res = registry.executeTool("search_logs", Map("query" -> "error"))
    assert(res.isError)
    assert(res.content.exists(_.head.text.contains("No active log session")))
  }

  test("McpDispatcher handles initialize, tools/list, and tools/call over mock session") {
    val tempFile = Files.createTempFile("umbrix-mcp-test", ".log")
    try {
      val logData =
        """2026-10-04T12:00:01.000Z [INFO] Service started successfully
          |2026-10-04T12:01:05.120Z [ERROR] Failed to connect to database: password=mysecret123
          |2026-10-04T12:02:10.500Z [WARN] Retrying connection attempt 1
          |2026-10-04T12:03:15.800Z [ERROR] NullPointerException in OrderService
          |2026-10-04T12:04:00.000Z [INFO] Healthcheck ok
          |""".stripMargin
      Files.writeString(tempFile, logData)

      val session = LogSession.open(tempFile, false)
      try {
        val registry = new ForensicToolRegistry(() => Some(session))
        val dispatcher = new McpDispatcher(registry)

        // 1. initialize
        val initRespJson = dispatcher.dispatch("""{"jsonrpc":"2.0","id":1,"method":"initialize"}""")
        val initResp = readFromString[JsonRpcResponse](initRespJson)
        assertEquals(initResp.id, Some(1L))
        assert(initResp.result.flatMap(_.message).exists(_.contains("initialized")))

        // 2. tools/list
        val listRespJson = dispatcher.dispatch("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        val listResp = readFromString[JsonRpcResponse](listRespJson)
        val toolNames = listResp.result.flatMap(_.tools).getOrElse(Nil).map(_.name)
        assert(toolNames.contains("search_logs"))
        assert(toolNames.contains("get_time_slice"))
        assert(toolNames.contains("extract_causal_context"))
        assert(toolNames.contains("get_error_clusters"))

        // 3. tools/call search_logs
        val searchCallJson =
          """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_logs","query":"ERROR"}}"""
        val searchRespJson = dispatcher.dispatch(searchCallJson)
        val searchResp = readFromString[JsonRpcResponse](searchRespJson)
        val text = searchResp.result.flatMap(_.content).getOrElse(Nil).head.text
        assert(text.contains("Found 2 matches"))
        assert(text.contains("[REDACTED]")) // verifies DLP ran on search results
        assert(!text.contains("mysecret123"))

        // 4. tools/call extract_causal_context
        val causalCallJson =
          """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"extract_causal_context","target_line":"1","backward_lines":"1","forward_lines":"1"}}"""
        val causalRespJson = dispatcher.dispatch(causalCallJson)
        val causalResp = readFromString[JsonRpcResponse](causalRespJson)
        val causalText = causalResp.result.flatMap(_.content).getOrElse(Nil).head.text
        assert(causalText.contains(">> [TARGET]"))

        // 5. tools/call get_error_clusters
        val clusterCallJson =
          """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_error_clusters","max_clusters":"5"}}"""
        val clusterRespJson = dispatcher.dispatch(clusterCallJson)
        val clusterResp = readFromString[JsonRpcResponse](clusterRespJson)
        val clusterText = clusterResp.result.flatMap(_.content).getOrElse(Nil).head.text
        assert(clusterText.contains("Top"))
      } finally {
        session.close()
      }
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }

  test("ForensicToolRegistry dynamically exposes registered plugin tools and sanitizes DLP output") {
    val tempFile = Files.createTempFile("umbrix-mcp-plugin-test", ".log")
    try {
      Files.writeString(tempFile, "2026-10-04 [INFO] Normal startup\n")
      val session = LogSession.open(tempFile, false)
      try {
        val pluginTool = new umbrix.plugin.api.mcp.McpToolProvider {
          def toolName: String = "plugin_dlp_tool"
          def description: String = "Test plugin tool emitting credentials"
          def parameters: Array[umbrix.plugin.api.mcp.PluginMcpParam] = Array(
            umbrix.plugin.api.mcp.PluginMcpParam("id", "string", "Test id", true)
          )
          def execute(session: umbrix.plugin.api.PluginSessionView, arguments: java.util.Map[String, String]): umbrix.plugin.api.mcp.PluginMcpToolResult = {
            umbrix.plugin.api.mcp.PluginMcpToolResult("Leaked credentials: password=supersecret123 and auth: Bearer eyJhbGciOi.eyJzdWIi.signature")
          }
        }
        val context = umbrix.core.plugin.PluginManager.instance.createContext("test-dlp-plugin", java.lang.foreign.Arena.ofShared())
        context.registerMcpTool(pluginTool)

        val registry = new ForensicToolRegistry(() => Some(session))
        val dispatcher = new McpDispatcher(registry)

        // 1. tools/list contains plugin tool
        val listRespJson = dispatcher.dispatch("""{"jsonrpc":"2.0","id":10,"method":"tools/list"}""")
        val listResp = readFromString[JsonRpcResponse](listRespJson)
        val toolNames = listResp.result.flatMap(_.tools).getOrElse(Nil).map(_.name)
        assert(toolNames.contains("plugin_dlp_tool"))

        // 2. tools/call plugin_dlp_tool redacts secrets
        val callJson = """{"jsonrpc":"2.0","id":11,"method":"tools/call","params":{"name":"plugin_dlp_tool","id":"x"}}"""
        val callRespJson = dispatcher.dispatch(callJson)
        val callResp = readFromString[JsonRpcResponse](callRespJson)
        val text = callResp.result.flatMap(_.content).getOrElse(Nil).head.text
        assert(text.contains("password=[REDACTED]"))
        assert(text.contains("Bearer [REDACTED_JWT]"))
        assert(!text.contains("supersecret123"))
      } finally {
        session.close()
      }
    } finally {
      Files.deleteIfExists(tempFile)
      umbrix.core.plugin.PluginManager.instance.close()
    }
  }

  test("McpDispatcher handles standard MCP tools/call with nested arguments map") {
    val tempFile = Files.createTempFile("umbrix-mcp-nested-test", ".log")
    try {
      val logData =
        """2026-10-04T12:00:01.000Z [INFO] Normal startup
          |2026-10-04T12:01:05.120Z [ERROR] Failed database write
          |""".stripMargin
      Files.writeString(tempFile, logData)
      val session = LogSession.open(tempFile, false)
      try {
        val registry = new ForensicToolRegistry(() => Some(session))
        val dispatcher = new McpDispatcher(registry)

        val nestedCallJson =
          """{"jsonrpc":"2.0","id":42,"method":"tools/call","params":{"name":"search_logs","arguments":{"query":"ERROR","max_results":10}}}"""
        val respJson = dispatcher.dispatch(nestedCallJson)
        val resp = readFromString[JsonRpcResponse](respJson)
        assertEquals(resp.id, Some(42L))
        val text = resp.result.flatMap(_.content).getOrElse(Nil).head.text
        assert(text.contains("Found 1 matches"))
        assert(text.contains("Failed database write"))
      } finally {
        session.close()
      }
    } finally {
      Files.deleteIfExists(tempFile)
    }
  }
}
