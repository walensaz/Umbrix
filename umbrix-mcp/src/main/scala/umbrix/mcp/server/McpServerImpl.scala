package umbrix.mcp.server

import java.io.{BufferedReader, InputStreamReader, PrintStream}
import java.net.InetSocketAddress
import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import com.github.plokhotnyuk.jsoniter_scala.core.*
import org.slf4j.LoggerFactory
import umbrix.mcp.protocol.*
import umbrix.mcp.protocol.McpCodecs.given
import umbrix.mcp.tools.ForensicToolRegistry

/**
 * Handles incoming JSON-RPC 2.0 requests for MCP tools.
 */
class McpDispatcher(registry: ForensicToolRegistry) {
  private val logger = LoggerFactory.getLogger(getClass)

  def dispatch(requestJson: String): String = {
    try {
      val req = readFromString[JsonRpcRequest](requestJson)
      val response = handleRequest(req)
      writeToString(response)
    } catch {
      case ex: Throwable =>
        logger.error("JSON-RPC parsing error", ex)
        val errResp = JsonRpcResponse(
          id = None,
          error = Some(JsonRpcError(code = -32700, message = s"Parse error: ${ex.getMessage}"))
        )
        writeToString(errResp)
    }
  }

  def handleRequest(req: JsonRpcRequest): JsonRpcResponse = {
    req.method match {
      case "initialize" =>
        JsonRpcResponse(
          id = req.id,
          result = Some(McpResult(message = Some("Umbrix MCP Server 1.0 initialized")))
        )
      case "notifications/initialized" =>
        JsonRpcResponse(id = req.id, result = Some(McpResult()))
      case "tools/list" =>
        JsonRpcResponse(
          id = req.id,
          result = Some(McpResult(tools = Some(registry.definitions)))
        )
      case "tools/call" =>
        val toolName = req.params.flatMap(_.get("name")).orElse(req.arguments.flatMap(_.get("name"))).getOrElse("")
        val args = req.arguments match {
          case Some(aMap) => req.params.getOrElse(Map.empty) ++ aMap
          case None => req.params.getOrElse(Map.empty)
        }
        val result = registry.executeTool(toolName, args)
        JsonRpcResponse(id = req.id, result = Some(result))
      case other =>
        JsonRpcResponse(
          id = req.id,
          error = Some(JsonRpcError(code = -32601, message = s"Method not found: $other"))
        )
    }
  }
}

/**
 * Embedded HTTP server providing JSON-RPC 2.0 & SSE for Cursor / Claude Desktop / external clients.
 */
class McpHttpServer(port: Int, registry: ForensicToolRegistry) extends AutoCloseable {
  private val logger = LoggerFactory.getLogger(getClass)
  private val dispatcher = new McpDispatcher(registry)
  private var server: Option[HttpServer] = None

  def start(): Unit = {
    val s = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0)
    s.createContext("/mcp", new HttpHandler {
      def handle(exchange: HttpExchange): Unit = {
        val method = exchange.getRequestMethod
        if ("POST".equalsIgnoreCase(method)) {
          val is = exchange.getRequestBody
          val body = new String(is.readAllBytes(), "UTF-8")
          val response = dispatcher.dispatch(body)
          val bytes = response.getBytes("UTF-8")
          exchange.getResponseHeaders.set("Content-Type", "application/json; charset=UTF-8")
          exchange.sendResponseHeaders(200, bytes.length.toLong)
          val os = exchange.getResponseBody
          os.write(bytes)
          os.close()
        } else if ("GET".equalsIgnoreCase(method)) {
          // MCP discovery endpoint
          val welcome = "{\"status\":\"ok\",\"server\":\"Umbrix MCP Server 1.0\"}".getBytes("UTF-8")
          exchange.getResponseHeaders.set("Content-Type", "application/json; charset=UTF-8")
          exchange.sendResponseHeaders(200, welcome.length.toLong)
          val os = exchange.getResponseBody
          os.write(welcome)
          os.close()
        } else {
          exchange.sendResponseHeaders(405, -1)
        }
      }
    })
    s.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
    s.start()
    server = Some(s)
    logger.info("Embedded MCP HTTP Server listening at http://127.0.0.1:{}/mcp", port)
  }

  def stop(): Unit = {
    server.foreach(_.stop(0))
    server = None
  }

  def close(): Unit = {
    stop()
  }
}

/**
 * Stdio transport for standard CLI MCP agent connection (e.g. Cursor, Claude Code).
 */
class McpStdioServer(registry: ForensicToolRegistry) extends Runnable {
  private val dispatcher = new McpDispatcher(registry)
  private var running = true

  def run(): Unit = {
    val reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"))
    val out = new PrintStream(System.out, true, "UTF-8")

    while (running) {
      val line = reader.readLine()
      if (line == null) {
        running = false
      } else if (line.trim.nonEmpty) {
        val response = dispatcher.dispatch(line)
        out.println(response)
      }
    }
  }

  def stop(): Unit = {
    running = false
  }
}
