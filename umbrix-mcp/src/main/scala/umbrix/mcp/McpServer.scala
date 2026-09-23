package umbrix.mcp

import umbrix.core.types.LineIndex
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType

/**
 * Model Context Protocol (MCP) server contract and forensic utilities.
 */
trait McpServer extends AutoCloseable {

  /** Starts the MCP server transport (stdio, SSE, or local Named Pipe). */
  def start(): Unit

  /** Stops the server and releases resources. */
  def stop(): Unit

  override def close(): Unit = {
    stop()
  }
}

object McpServer {
  /** Returns the count of BPE tokens for an LLM prompt using jtokkit. */
  def countPromptTokens(text: String): Int = {
    val registry = Encodings.newDefaultEncodingRegistry()
    val encoding = registry.getEncoding(EncodingType.CL100K_BASE)
    encoding.countTokens(text)
  }
}
