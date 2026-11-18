package umbrix.plugin.api.mcp

import umbrix.plugin.api.PluginSessionView

/**
 * Parameter definition for a tool exposed via the Model Context Protocol (MCP).
 */
final case class PluginMcpParam(
  name: String,
  paramType: String,
  description: String,
  required: Boolean = true
)

/**
 * Execution result returned by an MCP tool invocation to the AI model or MCP client.
 */
final case class PluginMcpToolResult(
  outputText: String,
  isError: Boolean = false
)

/**
 * SPI provider exposing domain-specific analysis tools to Umbrix's embedded Model Context Protocol (MCP) server.
 *
 * Allows external AI assistants (Claude, Cursor, Copilot) to inspect, query, and analyze
 * active forensic logs through standardized JSON-RPC tool calls.
 */
trait McpToolProvider {
  def toolName: String
  def description: String
  def parameters: Array[PluginMcpParam]

  def execute(
    session: PluginSessionView,
    arguments: java.util.Map[String, String]
  ): PluginMcpToolResult
}
