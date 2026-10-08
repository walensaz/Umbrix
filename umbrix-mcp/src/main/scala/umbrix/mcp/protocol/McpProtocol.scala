package umbrix.mcp.protocol

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

/**
 * Standard JSON-RPC 2.0 and MCP data contracts.
 * All serialization operates via jsoniter-scala with zero intermediate object copies.
 */

final case class JsonRpcRequest(
  jsonrpc: String = "2.0",
  id: Option[Long] = None,
  method: String,
  params: Option[Map[String, String]] = None
)

final case class JsonRpcResponse(
  jsonrpc: String = "2.0",
  id: Option[Long] = None,
  result: Option[McpResult] = None,
  error: Option[JsonRpcError] = None
)

final case class JsonRpcError(
  code: Int,
  message: String,
  data: Option[String] = None
)

final case class McpToolParamProperty(
  `type`: String,
  description: String,
  `enum`: Option[List[String]] = None,
  default: Option[String] = None
)

final case class McpToolInputSchema(
  `type`: String = "object",
  properties: Map[String, McpToolParamProperty],
  required: List[String] = Nil
)

final case class McpToolDefinition(
  name: String,
  description: String,
  inputSchema: McpToolInputSchema
)

final case class McpContentItem(
  `type`: String = "text",
  text: String
)

final case class McpResult(
  tools: Option[List[McpToolDefinition]] = None,
  content: Option[List[McpContentItem]] = None,
  isError: Boolean = false,
  message: Option[String] = None
)

object McpCodecs {
  given JsonValueCodec[JsonRpcRequest] = JsonCodecMaker.make
  given JsonValueCodec[JsonRpcResponse] = JsonCodecMaker.make
  given JsonValueCodec[McpToolParamProperty] = JsonCodecMaker.make
  given JsonValueCodec[McpToolInputSchema] = JsonCodecMaker.make
  given JsonValueCodec[McpToolDefinition] = JsonCodecMaker.make
  given JsonValueCodec[McpContentItem] = JsonCodecMaker.make
  given JsonValueCodec[McpResult] = JsonCodecMaker.make
}
