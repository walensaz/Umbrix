package umbrix.core.plugin

import java.lang.foreign.{Arena, MemorySegment}
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
import scala.jdk.CollectionConverters.*
import org.slf4j.LoggerFactory
import umbrix.plugin.api.*
import umbrix.plugin.api.action.{PluginAction, PluginActionLocation}
import umbrix.plugin.api.config.{PluginConfigStore, PluginSettingDescriptor}
import umbrix.plugin.api.decorator.{DocumentDecorator, GutterDecoration, HoverCardDecoration, InlineChipDecoration}
import umbrix.plugin.api.format.{FileFormatProvider, FormatProbeResult}
import umbrix.plugin.api.mcp.McpToolProvider
import umbrix.plugin.api.query.QueryFunction
import umbrix.plugin.api.simd.SimdHelper
import umbrix.plugin.api.ui.DockViewFactory

/**
 * State container for an installed plugin JAR.
 */
final case class InstalledPlugin(
  manifest: PluginManifest,
  jarPath: Path,
  pluginInstance: UmbrixPlugin,
  classLoader: PluginClassLoader,
  var arena: Arena,
  var isEnabled: Boolean,
  var isPendingRestart: Boolean,
  var context: PluginContext
)

/**
 * Default headless workspace controller used when running without a desktop UI window.
 */
class HeadlessPluginWorkspaceController extends PluginWorkspaceController {
  @volatile var activeSessionHolder: PluginSessionView = null

  def activeSession: PluginSessionView = activeSessionHolder
  def navigateToRecord(recordIndex: Long): Unit = ()
  def navigateToTimestampMicros(epochMicros: Long): Unit = ()
  def executeSearchQuery(queryText: String, filterMode: Boolean = false): Unit = ()
  def openScratchpadWithText(title: String, content: String): Unit = ()
  def appendToActiveScratchpad(text: String): Unit = ()
  def openDockView(viewId: String, dropZone: String = "RIGHT"): Unit = ()
  def showToast(message: String, severity: String = "INFO"): Unit = ()
  def openFile(path: Path): Unit = ()
  def openFileDialog(dialogTitle: String, fileExtensionFilter: String): Unit = ()
}

/**
 * Central runtime coordinator for Umbrix plugins.
 *
 * Discovers plugin JARs from `~/.umbrix/plugins/`, manages child-first `PluginClassLoader`
 * lifecycles, supervises third-party execution via circuit breaker, and aggregates extension
 * point registrations (file formats, SIMD kernels, decorators, dock views, MCP tools, and actions).
 */
class PluginManager(
  val pluginsDirectory: Path = Paths.get(System.getProperty("user.home"), ".umbrix", "plugins"),
  val faultSupervisor: PluginFaultSupervisor = new PluginFaultSupervisor(),
  val simdHelper: SimdHelper = new SimdHelperImpl()
) extends AutoCloseable {

  private val logger = LoggerFactory.getLogger(getClass)

  @volatile private var workspaceController: PluginWorkspaceController = new HeadlessPluginWorkspaceController()

  private val installedPlugins = new ConcurrentHashMap[String, InstalledPlugin]()
  private val formatProviders = new CopyOnWriteArrayList[(String, FileFormatProvider)]()
  private val decorators = new CopyOnWriteArrayList[(String, DocumentDecorator)]()
  private val dockViewFactories = new ConcurrentHashMap[String, (String, DockViewFactory)]()
  private val mcpToolProviders = new ConcurrentHashMap[String, (String, McpToolProvider)]()
  private val queryFunctions = new ConcurrentHashMap[String, (String, QueryFunction)]()
  private val actions = new CopyOnWriteArrayList[(String, PluginAction)]()
  private val sessionListeners = new CopyOnWriteArrayList[(String, PluginSessionListener)]()
  private val settingsSchemas = new ConcurrentHashMap[String, Array[PluginSettingDescriptor]]()

  def setWorkspaceController(controller: PluginWorkspaceController): Unit = {
    this.workspaceController = controller
  }

  def getWorkspaceController: PluginWorkspaceController = workspaceController

  /**
   * Initializes the plugin subsystem, discovering all JARs in `pluginsDirectory`.
   */
  def initialize(disabledPluginIds: Set[String] = Set.empty): Unit = {
    if (!Files.exists(pluginsDirectory)) {
      Files.createDirectories(pluginsDirectory)
    }

    val stream = Files.newDirectoryStream(pluginsDirectory, "*.jar")
    try {
      val jarPaths = stream.asScala.toList.sortBy(_.getFileName.toString)
      for (jarPath <- jarPaths) {
        try {
          loadPluginJar(jarPath, disabledPluginIds)
        } catch {
          case t: Throwable =>
            logger.error(s"Failed to load plugin JAR '${jarPath.getFileName}': ${t.getMessage}", t)
        }
      }
    } finally {
      stream.close()
    }
  }

  /**
   * Copies an external `.jar` into `pluginsDirectory` and loads it dynamically.
   */
  def installPluginJar(sourceJarPath: Path): PluginManifest = {
    if (!Files.exists(pluginsDirectory)) {
      Files.createDirectories(pluginsDirectory)
    }
    val manifest = PluginManifestParser.parseFromJar(sourceJarPath)
    val targetJarPath = pluginsDirectory.resolve(sourceJarPath.getFileName.toString)
    if (!targetJarPath.equals(sourceJarPath)) {
      Files.copy(sourceJarPath, targetJarPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
    loadPluginJar(targetJarPath, Set.empty)
    manifest
  }

  private def loadPluginJar(jarPath: Path, disabledPluginIds: Set[String]): Unit = {
    val manifest = PluginManifestParser.parseFromJar(jarPath)
    if (installedPlugins.containsKey(manifest.id)) {
      val old = installedPlugins.remove(manifest.id)
      if (old != null) {
        if (old.isEnabled) {
          try { old.pluginInstance.onDisable() } catch { case _: Throwable => () }
        }
        unregisterHooksForPlugin(manifest.id)
        try { old.arena.close() } catch { case _: Throwable => () }
        try { old.classLoader.close() } catch { case _: Throwable => () }
      }
    }
    val classLoader = new PluginClassLoader(jarPath, getClass.getClassLoader)
    val pluginClass = classLoader.loadClass(manifest.entrypoint)
    val pluginInstance = pluginClass.getDeclaredConstructor().newInstance().asInstanceOf[UmbrixPlugin]

    val arena = Arena.ofShared()
    val context = createContext(manifest.id, arena)
    val loaded = InstalledPlugin(
      manifest = manifest,
      jarPath = jarPath,
      pluginInstance = pluginInstance,
      classLoader = classLoader,
      arena = arena,
      isEnabled = false,
      isPendingRestart = false,
      context = context
    )

    installedPlugins.put(manifest.id, loaded)

    if (!disabledPluginIds.contains(manifest.id)) {
      enablePlugin(manifest.id)
    }
  }

  def createContext(targetPluginId: String, arena: Arena): PluginContext = {
    val pluginDataDir = pluginsDirectory.resolve(targetPluginId).resolve("data")
    val configFile = pluginsDirectory.resolve(targetPluginId).resolve("config.json")
    val store = new PluginConfigStoreImpl(configFile)

    new PluginContext {
      def pluginId: String = targetPluginId
      def pluginDataDirectory: Path = pluginDataDir
      def pluginArena: Arena = arena
      def simdHelper: SimdHelper = PluginManager.this.simdHelper
      def configStore: PluginConfigStore = store
      def workspaceController: PluginWorkspaceController = PluginManager.this.workspaceController

      def registerSettingsSchema(descriptors: Array[PluginSettingDescriptor]): Unit = {
        settingsSchemas.put(targetPluginId, descriptors)
      }

      def registerFormatProvider(provider: FileFormatProvider): Unit = {
        formatProviders.add((targetPluginId, provider))
      }

      def registerDecorator(decorator: DocumentDecorator): Unit = {
        decorators.add((targetPluginId, decorator))
      }

      def registerDockView(factory: DockViewFactory): Unit = {
        dockViewFactories.put(factory.viewId, (targetPluginId, factory))
      }

      def registerMcpTool(provider: McpToolProvider): Unit = {
        mcpToolProviders.put(provider.toolName, (targetPluginId, provider))
      }

      def registerQueryFunction(function: QueryFunction): Unit = {
        queryFunctions.put(function.name.toLowerCase, (targetPluginId, function))
      }

      def registerAction(action: PluginAction): Unit = {
        actions.add((targetPluginId, action))
      }

      def registerSessionListener(listener: PluginSessionListener): Unit = {
        sessionListeners.add((targetPluginId, listener))
      }
    }
  }

  def enablePlugin(pluginId: String): Unit = {
    val loaded = installedPlugins.get(pluginId)
    if (loaded != null && !loaded.isEnabled) {
      if (loaded.manifest.requiresRestart && loaded.isPendingRestart) {
        logger.info(s"Plugin '$pluginId' requires application restart to re-enable.")
      } else {
        faultSupervisor.executeGuarded(pluginId, "onEnable", ()) {
          loaded.pluginInstance.onEnable(loaded.context)
        }
        loaded.isEnabled = true
        loaded.isPendingRestart = false
        logger.info(s"Enabled plugin '${loaded.manifest.name}' (${loaded.manifest.id} v${loaded.manifest.version})")
      }
    }
  }

  def disablePlugin(pluginId: String): Unit = {
    val loaded = installedPlugins.get(pluginId)
    if (loaded != null && loaded.isEnabled) {
      if (loaded.manifest.requiresRestart) {
        loaded.isPendingRestart = true
        logger.info(s"Plugin '$pluginId' marked pending restart for disable.")
      } else {
        faultSupervisor.executeGuarded(pluginId, "onDisable", ()) {
          loaded.pluginInstance.onDisable()
        }
        unregisterHooksForPlugin(pluginId)
        loaded.arena.close()
        // Re-allocate arena and context for future re-enabling
        loaded.arena = Arena.ofShared()
        loaded.context = createContext(pluginId, loaded.arena)
        loaded.isEnabled = false
        loaded.isPendingRestart = false
        logger.info(s"Disabled plugin '$pluginId'.")
      }
    }
  }

  private def unregisterHooksForPlugin(pluginId: String): Unit = {
    formatProviders.removeIf(_._1 == pluginId)
    decorators.removeIf(_._1 == pluginId)
    dockViewFactories.values().removeIf(_._1 == pluginId)
    mcpToolProviders.values().removeIf(_._1 == pluginId)
    queryFunctions.values().removeIf(_._1 == pluginId)
    actions.removeIf(_._1 == pluginId)
    sessionListeners.removeIf(_._1 == pluginId)
    settingsSchemas.remove(pluginId)
  }

  def allInstalledPlugins: java.util.List[InstalledPlugin] = {
    val list = new java.util.ArrayList[InstalledPlugin](installedPlugins.values())
    list.sort((a, b) => a.manifest.name.compareToIgnoreCase(b.manifest.name))
    list
  }

  def getInstalledPlugin(pluginId: String): Option[InstalledPlugin] = {
    Option(installedPlugins.get(pluginId))
  }

  def isPluginEnabled(pluginId: String): Boolean = {
    val loaded = installedPlugins.get(pluginId)
    loaded != null && loaded.isEnabled
  }

  def reloadPlugins(disabledPluginIds: Set[String] = Set.empty): Unit = {
    close()
    initialize(disabledPluginIds)
  }

  // --- Extension Point Resolution ---

  def probeFormatWithProvider(path: Path, headerSlice: MemorySegment, fileSizeBytes: Long): Option[(FileFormatProvider, FormatProbeResult)] = {
    var bestMatch: Option[(FileFormatProvider, FormatProbeResult)] = None
    var bestPriority = -1
    val iter = formatProviders.iterator()
    while (iter.hasNext) {
      val (pluginId, provider) = iter.next()
      if (!faultSupervisor.isTripped(pluginId)) {
        val result = faultSupervisor.executeGuarded(pluginId, "probe", FormatProbeResult.NotMatched) {
          provider.probe(path, headerSlice, fileSizeBytes)
        }
        if (result.matched && result.priority > bestPriority) {
          bestMatch = Some((provider, result))
          bestPriority = result.priority
        }
      }
    }
    bestMatch
  }

  def probeFormat(path: Path, headerSlice: MemorySegment, fileSizeBytes: Long): FormatProbeResult = {
    probeFormatWithProvider(path, headerSlice, fileSizeBytes).map(_._2).getOrElse(FormatProbeResult.NotMatched)
  }

  def getFormatProvider(providerId: String): Option[FileFormatProvider] = {
    formatProviders.asScala.find(_._2.id == providerId).map(_._2)
  }

  def resolveGutterDecoration(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment
  ): GutterDecoration = {
    var resolved: GutterDecoration = null
    val iter = decorators.iterator()
    while (iter.hasNext && resolved == null) {
      val (pluginId, decorator) = iter.next()
      if (!faultSupervisor.isTripped(pluginId)) {
        resolved = faultSupervisor.executeGuarded(pluginId, "resolveGutterDecoration", null.asInstanceOf[GutterDecoration]) {
          decorator.resolveGutterDecoration(session, recordIndex, rawRecordSlice)
        }
      }
    }
    resolved
  }

  def resolveInlineChips(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment,
    shapedRecordText: String,
    targetList: java.util.List[InlineChipDecoration]
  ): Unit = {
    val iter = decorators.iterator()
    while (iter.hasNext) {
      val (pluginId, decorator) = iter.next()
      if (!faultSupervisor.isTripped(pluginId)) {
        faultSupervisor.executeGuarded(pluginId, "resolveInlineChips", ()) {
          decorator.resolveInlineChips(session, recordIndex, rawRecordSlice, shapedRecordText, targetList)
        }
      }
    }
  }

  def resolveHoverCard(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment,
    shapedRecordText: String,
    charColumn: Int
  ): HoverCardDecoration = {
    var resolved: HoverCardDecoration = null
    val iter = decorators.iterator()
    while (iter.hasNext && resolved == null) {
      val (pluginId, decorator) = iter.next()
      if (!faultSupervisor.isTripped(pluginId)) {
        resolved = faultSupervisor.executeGuarded(pluginId, "resolveHoverCard", null.asInstanceOf[HoverCardDecoration]) {
          decorator.resolveHoverCard(session, recordIndex, rawRecordSlice, shapedRecordText, charColumn)
        }
      }
    }
    resolved
  }

  def canExpandRecord(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment
  ): Boolean = {
    var canExpand = false
    val iter = decorators.iterator()
    while (iter.hasNext && !canExpand) {
      val (pluginId, decorator) = iter.next()
      if (!faultSupervisor.isTripped(pluginId)) {
        canExpand = faultSupervisor.executeGuarded(pluginId, "canExpandRecord", false) {
          decorator.canExpandRecord(session, recordIndex, rawRecordSlice)
        }
      }
    }
    canExpand
  }

  def populateVirtualExpansion(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment
  ): Unit = {
    val iter = decorators.iterator()
    while (iter.hasNext) {
      val (pluginId, decorator) = iter.next()
      if (!faultSupervisor.isTripped(pluginId)) {
        faultSupervisor.executeGuarded(pluginId, "populateVirtualExpansion", ()) {
          if (decorator.canExpandRecord(session, recordIndex, rawRecordSlice)) {
            decorator.populateVirtualExpansion(session, recordIndex, rawRecordSlice)
          }
        }
      }
    }
  }

  def applicableContextMenuActions(
    session: PluginSessionView,
    recordIndex: Long,
    recordText: String,
    selectedText: String
  ): java.util.List[PluginAction] = {
    val list = new java.util.ArrayList[PluginAction]()
    val iter = actions.iterator()
    while (iter.hasNext) {
      val (pluginId, action) = iter.next()
      if (!faultSupervisor.isTripped(pluginId) &&
        (action.location == PluginActionLocation.VIEWPORT_CONTEXT_MENU ||
          action.location == PluginActionLocation.BOTH ||
          action.location == PluginActionLocation.ALL)) {
        val ok = faultSupervisor.executeGuarded(pluginId, "isApplicable", false) {
          action.isApplicable(session, recordIndex, recordText, selectedText)
        }
        if (ok) {
          list.add(action)
        }
      }
    }
    list
  }

  def commandPaletteActions: java.util.List[PluginAction] = {
    val list = new java.util.ArrayList[PluginAction]()
    val iter = actions.iterator()
    while (iter.hasNext) {
      val (pluginId, action) = iter.next()
      if (!faultSupervisor.isTripped(pluginId) &&
        (action.location == PluginActionLocation.COMMAND_PALETTE ||
          action.location == PluginActionLocation.BOTH ||
          action.location == PluginActionLocation.ALL)) {
        list.add(action)
      }
    }
    list
  }

  def hamburgerMenuActions: java.util.List[PluginAction] = {
    val list = new java.util.ArrayList[PluginAction]()
    val iter = actions.iterator()
    while (iter.hasNext) {
      val (pluginId, action) = iter.next()
      if (!faultSupervisor.isTripped(pluginId) &&
        (action.location == PluginActionLocation.HAMBURGER_MENU ||
          action.location == PluginActionLocation.ALL)) {
        list.add(action)
      }
    }
    list
  }

  def getQueryFunction(name: String): Option[QueryFunction] = {
    Option(queryFunctions.get(name.toLowerCase)).filterNot(t => faultSupervisor.isTripped(t._1)).map(_._2)
  }

  def allMcpToolProviders: java.util.List[McpToolProvider] = {
    val list = new java.util.ArrayList[McpToolProvider]()
    val iter = mcpToolProviders.values().iterator()
    while (iter.hasNext) {
      val (pluginId, provider) = iter.next()
      if (!faultSupervisor.isTripped(pluginId)) {
        list.add(provider)
      }
    }
    list
  }

  def getDockViewFactory(viewId: String): Option[DockViewFactory] = {
    Option(dockViewFactories.get(viewId)).filterNot(t => faultSupervisor.isTripped(t._1)).map(_._2)
  }

  def getPluginIdForDockView(viewId: String): Option[String] = {
    Option(dockViewFactories.get(viewId)).map(_._1)
  }

  def getPluginConfigStore(pluginId: String): Option[PluginConfigStore] = {
    Option(installedPlugins.get(pluginId)).map(_.context.configStore).orElse {
      val configFile = pluginsDirectory.resolve(pluginId).resolve("config.json")
      if (Files.exists(configFile)) {
        Some(new PluginConfigStoreImpl(configFile))
      } else {
        None
      }
    }
  }

  def allDockViewFactories: java.util.List[DockViewFactory] = {
    val list = new java.util.ArrayList[DockViewFactory]()
    val iter = dockViewFactories.values().iterator()
    while (iter.hasNext) {
      val (pluginId, factory) = iter.next()
      if (!faultSupervisor.isTripped(pluginId)) {
        list.add(factory)
      }
    }
    list
  }

  def getSettingsSchema(pluginId: String): Option[Array[PluginSettingDescriptor]] = {
    Option(settingsSchemas.get(pluginId))
  }

  def notifySessionOpened(session: PluginSessionView): Unit = {
    workspaceController match {
      case headless: HeadlessPluginWorkspaceController =>
        headless.activeSessionHolder = session
      case _ => ()
    }
    val iter = sessionListeners.iterator()
    while (iter.hasNext) {
      val (pluginId, listener) = iter.next()
      if (!faultSupervisor.isTripped(pluginId)) {
        faultSupervisor.executeGuarded(pluginId, "onSessionOpened", ()) {
          listener.onSessionOpened(session)
        }
      }
    }
  }

  def notifySessionClosing(session: PluginSessionView): Unit = {
    workspaceController match {
      case headless: HeadlessPluginWorkspaceController =>
        if (headless.activeSessionHolder eq session) {
          headless.activeSessionHolder = null
        }
      case _ => ()
    }
    val iter = sessionListeners.iterator()
    while (iter.hasNext) {
      val (pluginId, listener) = iter.next()
      if (!faultSupervisor.isTripped(pluginId)) {
        faultSupervisor.executeGuarded(pluginId, "onSessionClosing", ()) {
          listener.onSessionClosing(session)
        }
      }
    }
  }

  def close(): Unit = {
    for (entry <- installedPlugins.values().asScala) {
      try {
        if (entry.isEnabled) {
          entry.pluginInstance.onDisable()
        }
        entry.arena.close()
        entry.classLoader.close()
      } catch {
        case t: Throwable =>
          logger.warn(s"Error closing plugin '${entry.manifest.id}': ${t.getMessage}")
      }
    }
    installedPlugins.clear()
    formatProviders.clear()
    decorators.clear()
    dockViewFactories.clear()
    mcpToolProviders.clear()
    queryFunctions.clear()
    actions.clear()
    sessionListeners.clear()
    settingsSchemas.clear()
  }
}

object PluginManager {
  private val defaultInstance = new PluginManager()
  def instance: PluginManager = defaultInstance
}
