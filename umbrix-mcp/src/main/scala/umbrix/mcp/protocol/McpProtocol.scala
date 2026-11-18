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
  params: Option[Map[String, String]] = None,
  arguments: Option[Map[String, String]] = None
) {
  /** Resolves tool name from params("name") or arguments("name"). */
  def toolName: String = params.flatMap(_.get("name")).orElse(arguments.flatMap(_.get("name"))).getOrElse("")

  /** Resolves tool arguments prioritizing nested arguments map over flat params map. */
  def toolArguments: Map[String, String] = {
    arguments match {
      case Some(aMap) => params.getOrElse(Map.empty) ++ aMap
      case None => params.getOrElse(Map.empty)
    }
  }
}

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
  given JsonValueCodec[JsonRpcResponse] = JsonCodecMaker.make
  given JsonValueCodec[McpToolParamProperty] = JsonCodecMaker.make
  given JsonValueCodec[McpToolInputSchema] = JsonCodecMaker.make
  given JsonValueCodec[McpToolDefinition] = JsonCodecMaker.make
  given JsonValueCodec[McpContentItem] = JsonCodecMaker.make
  given JsonValueCodec[McpResult] = JsonCodecMaker.make

  given JsonValueCodec[JsonRpcRequest] = new JsonValueCodec[JsonRpcRequest] {
    def nullValue: JsonRpcRequest = null

    def encodeValue(req: JsonRpcRequest, out: JsonWriter): Unit = {
      out.writeObjectStart()
      out.writeKey("jsonrpc")
      out.writeVal(req.jsonrpc)
      req.id.foreach { idVal =>
        out.writeKey("id")
        out.writeVal(idVal)
      }
      out.writeKey("method")
      out.writeVal(req.method)
      req.params.foreach { p =>
        out.writeKey("params")
        out.writeObjectStart()
        p.foreach { (k, v) =>
          out.writeKey(k)
          out.writeVal(v)
        }
        out.writeObjectEnd()
      }
      req.arguments.foreach { args =>
        if (req.params.isEmpty) {
          out.writeKey("arguments")
          out.writeObjectStart()
          args.foreach { (k, v) =>
            out.writeKey(k)
            out.writeVal(v)
          }
          out.writeObjectEnd()
        }
      }
      out.writeObjectEnd()
    }

    def decodeValue(in: JsonReader, default: JsonRpcRequest): JsonRpcRequest = {
      if (in.isNextToken('{')) {
        var jsonrpc = "2.0"
        var id: Option[Long] = None
        var method: String = ""
        var paramsMap: Option[Map[String, String]] = None
        var argumentsMap: Option[Map[String, String]] = None

        if (!in.isNextToken('}')) {
          in.rollbackToken()
          var continueOuter = true
          while (continueOuter) {
            val key = in.readKeyAsString()
            key match {
              case "jsonrpc" =>
                jsonrpc = in.readString(jsonrpc)
              case "id" =>
                val b = in.nextToken()
                if (b == 'n') {
                  in.readNullOrError(null, "expected null")
                  id = None
                } else {
                  in.rollbackToken()
                  id = Some(in.readLong())
                }
              case "method" =>
                method = in.readString(method)
              case "params" =>
                val b = in.nextToken()
                if (b == 'n') {
                  in.readNullOrError(null, "expected null")
                  paramsMap = None
                } else if (b == '{') {
                  val pMap = scala.collection.mutable.LinkedHashMap[String, String]()
                  val aMap = scala.collection.mutable.LinkedHashMap[String, String]()
                  if (!in.isNextToken('}')) {
                    in.rollbackToken()
                    var continueParams = true
                    while (continueParams) {
                      val pKey = in.readKeyAsString()
                      val valToken = in.nextToken()
                      if (pKey == "arguments" && valToken == '{') {
                        if (!in.isNextToken('}')) {
                          in.rollbackToken()
                          var continueArgs = true
                          while (continueArgs) {
                            val aKey = in.readKeyAsString()
                            val aVal = readScalarOrRaw(in)
                            aMap.put(aKey, aVal)
                            pMap.put(aKey, aVal)
                            continueArgs = in.isNextToken(',')
                          }
                          if (!in.isCurrentToken('}')) {
                            in.objectEndOrCommaError()
                          }
                        }
                      } else if (valToken == '{' || valToken == '[') {
                        in.rollbackToken()
                        val rawJson = new String(in.readRawValAsBytes(), java.nio.charset.StandardCharsets.UTF_8).trim
                        pMap.put(pKey, rawJson)
                      } else {
                        in.rollbackToken()
                        val scalar = readScalarOrRaw(in)
                        pMap.put(pKey, scalar)
                      }
                      continueParams = in.isNextToken(',')
                    }
                    if (!in.isCurrentToken('}')) {
                      in.objectEndOrCommaError()
                    }
                  }
                  paramsMap = Some(pMap.toMap)
                  if (aMap.nonEmpty) {
                    argumentsMap = Some(aMap.toMap)
                  }
                } else {
                  in.skip()
                }
              case "arguments" =>
                val b = in.nextToken()
                if (b == '{') {
                  val aMap = scala.collection.mutable.LinkedHashMap[String, String]()
                  if (!in.isNextToken('}')) {
                    in.rollbackToken()
                    var continueArgs = true
                    while (continueArgs) {
                      val aKey = in.readKeyAsString()
                      val aVal = readScalarOrRaw(in)
                      aMap.put(aKey, aVal)
                      continueArgs = in.isNextToken(',')
                    }
                    if (!in.isCurrentToken('}')) {
                      in.objectEndOrCommaError()
                    }
                  }
                  argumentsMap = Some(aMap.toMap)
                } else {
                  in.rollbackToken()
                  in.skip()
                }
              case _ =>
                in.skip()
            }
            continueOuter = in.isNextToken(',')
          }
          if (!in.isCurrentToken('}')) {
            in.objectEndOrCommaError()
          }
        }
        JsonRpcRequest(jsonrpc, id, method, paramsMap, argumentsMap)
      } else {
        in.readNullOrTokenError(default, '{'.toByte)
      }
    }

    private def readScalarOrRaw(in: JsonReader): String = {
      val b = in.nextToken()
      if (b == '"') {
        in.rollbackToken()
        in.readString(null)
      } else if (b == 'n') {
        in.readNullOrError(null, "expected null")
        ""
      } else {
        in.rollbackToken()
        new String(in.readRawValAsBytes(), java.nio.charset.StandardCharsets.UTF_8).trim
      }
    }
  }
}
