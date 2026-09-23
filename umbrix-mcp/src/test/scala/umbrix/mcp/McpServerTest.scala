package umbrix.mcp

import munit.FunSuite
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

final case class JsonRpcRequest(
  jsonrpc: String,
  id: Long,
  method: String
)

class McpServerTest extends FunSuite {

  given JsonValueCodec[JsonRpcRequest] = JsonCodecMaker.make

  test("McpServer token counter accurately tokenizes sample text") {
    val tokens = McpServer.countPromptTokens("Umbrix log workstation initialized.")
    assert(tokens > 0)
  }

  test("jsoniter-scala serializes and deserializes JSON-RPC MCP payload") {
    val request = JsonRpcRequest(jsonrpc = "2.0", id = 1L, method = "search_logs")
    val bytes = writeToArray(request)
    val parsed = readFromArray[JsonRpcRequest](bytes)
    assertEquals(parsed.jsonrpc, "2.0")
    assertEquals(parsed.id, 1L)
    assertEquals(parsed.method, "search_logs")
  }
}
