package umbrix.plugin.api

import java.lang.foreign.{Arena, MemorySegment}
import java.nio.file.Path
import umbrix.plugin.api.action.PluginAction
import umbrix.plugin.api.config.{PluginConfigStore, PluginSettingDescriptor}
import umbrix.plugin.api.decorator.DocumentDecorator
import umbrix.plugin.api.format.FileFormatProvider
import umbrix.plugin.api.mcp.McpToolProvider
import umbrix.plugin.api.query.QueryFunction
import umbrix.plugin.api.simd.SimdHelper
import umbrix.plugin.api.ui.DockViewFactory

/**
 * Architectural capabilities that an Umbrix plugin can contribute to the host platform.
 */
enum PluginCapability extends java.lang.Enum[PluginCapability] {
  case FILE_FORMAT
  case SIMD_ANALYZER
  case DOCUMENT_DECORATOR
  case DOCK_VIEW
  case MCP_TOOL
  case QUERY_FUNCTION
  case ACTION
}

/**
 * Immutable metadata and capabilities descriptor parsed from `META-INF/umbrix-plugin.json`.
 */
final case class PluginManifest(
  id: String,
  name: String,
  version: String,
  vendor: String,
  description: String,
  minUmbrixVersion: String,
  requiresRestart: Boolean,
  capabilities: Array[PluginCapability],
  entrypoint: String
)

/**
 * Read-only forensic view of an open document/log session provided to plugins.
 *
 * Exposes zero-copy off-heap memory segment access, coordinate translation between
 * record indices and 64-bit byte offsets, clamped record text, and non-destructive
 * virtual expansion annotations via the piece table.
 */
trait PluginSessionView {
  def sessionId: String
  def filePath: Path
  def fileName: String
  def fileSizeBytes: Long
  def totalRecords: Long
  def simdVectorByteWidth: Int
  /** Identifier of the matched [[umbrix.plugin.api.format.FileFormatProvider]], or empty string for plain logs. */
  def activeFormatId: String = ""

  def readSlice(offset: Long, length: Long): MemorySegment
  def getRecordSlice(recordIndex: Long): MemorySegment
  def readRecordBytes(recordIndex: Long, maxBytes: Int = 65536): Array[Byte]
  def byteOffsetForRecord(recordIndex: Long): Long
  def recordIndexForByteOffset(byteOffset: Long): Long
  def getClampedRecordText(recordIndex: Long, maxChars: Int = 4096): String
  def putVirtualExpansion(recordIndex: Long, utf8Payload: MemorySegment, lineCount: Int): Unit
  def putVirtualExpansionText(recordIndex: Long, multiLineText: String): Unit
  def clearVirtualExpansion(recordIndex: Long): Unit
  def hasVirtualExpansion(recordIndex: Long): Boolean
}

/**
 * Root service SPI contract for Umbrix extensions.
 *
 * Implementations are discovered via Java SPI (`META-INF/services/umbrix.plugin.api.UmbrixPlugin`)
 * and instantiated on startup. `onEnable` registers custom providers, actions, and views.
 */
trait UmbrixPlugin {
  def id: String
  def name: String
  def onEnable(context: PluginContext): Unit
  def onDisable(): Unit = {}
}

/**
 * Host registration context supplied to [[UmbrixPlugin.onEnable]].
 *
 * Allows plugins to register format providers, decorators, dock views, MCP tools,
 * query functions, context actions, settings schemas, and session lifecycle listeners.
 */
trait PluginContext {
  def pluginId: String
  def pluginDataDirectory: Path
  def pluginArena: Arena
  def simdHelper: SimdHelper
  def configStore: PluginConfigStore
  def workspaceController: PluginWorkspaceController

  def registerSettingsSchema(descriptors: Array[PluginSettingDescriptor]): Unit
  def registerFormatProvider(provider: FileFormatProvider): Unit
  def registerDecorator(decorator: DocumentDecorator): Unit
  def registerDockView(factory: DockViewFactory): Unit
  def registerMcpTool(provider: McpToolProvider): Unit
  def registerQueryFunction(function: QueryFunction): Unit
  def registerAction(action: PluginAction): Unit
  def registerSessionListener(listener: PluginSessionListener): Unit
}

/**
 * Host workspace controller allowing plugins to drive UI navigation and interaction.
 *
 * Enables record and timestamp navigation, search query execution, dock view activation,
 * scratchpad manipulation, and user notifications.
 */
trait PluginWorkspaceController {
  def activeSession: PluginSessionView
  def navigateToRecord(recordIndex: Long): Unit
  def navigateToTimestampMicros(epochMicros: Long): Unit
  def executeSearchQuery(queryText: String, filterMode: Boolean = false): Unit
  def openScratchpadWithText(title: String, content: String): Unit
  def appendToActiveScratchpad(text: String): Unit
  def openDockView(viewId: String, dropZone: String = "RIGHT"): Unit
  def showToast(message: String, severity: String = "INFO"): Unit
  def openFile(path: java.nio.file.Path): Unit
  def openFileDialog(dialogTitle: String, fileExtensionFilter: String): Unit
}

/**
 * Lifecycle observer receiving callbacks when log sessions are opened or closed.
 */
trait PluginSessionListener {
  def onSessionOpened(session: PluginSessionView): Unit = {}
  def onSessionClosing(session: PluginSessionView): Unit = {}
}
