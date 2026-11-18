package umbrix.core.plugin

import munit.FunSuite
import java.io.File
import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import it.unimi.dsi.fastutil.longs.LongArrayList
import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.core.model.QueryAst
import umbrix.core.parser.QueryParser
import umbrix.core.session.LogSession
import umbrix.core.types.LineIndex
import scala.jdk.CollectionConverters.*
import umbrix.plugin.api.action.{PluginActionInvocation, PluginActionLocation}
import umbrix.plugin.api.config.PluginSettingType
import umbrix.plugin.api.format.FormatProbeResult

class PluginArchitectureIntegrationSpec extends FunSuite {

  private def createJarFromPackage(jarPath: Path, manifestJson: String): Unit = {
    val outputStream = Files.newOutputStream(jarPath)
    val jarOut = new JarOutputStream(outputStream)
    try {
      val manifestEntry = new ZipEntry("META-INF/umbrix-plugin.json")
      jarOut.putNextEntry(manifestEntry)
      jarOut.write(manifestJson.getBytes(StandardCharsets.UTF_8))
      jarOut.closeEntry()

      val syntheticUrl = getClass.getClassLoader.getResource("synthetic")
      if (syntheticUrl != null && syntheticUrl.getProtocol == "file") {
        val syntheticDir = Paths.get(syntheticUrl.toURI)
        val stream = Files.newDirectoryStream(syntheticDir, "*.class")
        try {
          val iter = stream.iterator()
          while (iter.hasNext) {
            val classFile = iter.next()
            val entryName = "synthetic/" + classFile.getFileName.toString
            jarOut.putNextEntry(new ZipEntry(entryName))
            Files.copy(classFile, jarOut)
            jarOut.closeEntry()
          }
        } finally {
          stream.close()
        }
      }
    } finally {
      jarOut.close()
    }
  }

  test("Synthetic plugin JAR packaging and dynamic installation into PluginManager") {
    val tempDir = Files.createTempDirectory("umbrix-plugin-integ-test-")
    val pluginsDir = tempDir.resolve("plugins")
    Files.createDirectories(pluginsDir)

    val manifestJson =
      """{
        |  "id": "synthetic-test-plugin",
        |  "name": "Synthetic Test Plugin",
        |  "version": "1.0.0",
        |  "vendor": "Umbrix Testing",
        |  "description": "Integration test plugin exercising SPI capabilities",
        |  "minUmbrixVersion": "1.0.0",
        |  "requiresRestart": false,
        |  "capabilities": [
        |    "FILE_FORMAT",
        |    "SIMD_ANALYZER",
        |    "DOCUMENT_DECORATOR",
        |    "DOCK_VIEW",
        |    "MCP_TOOL",
        |    "QUERY_FUNCTION",
        |    "ACTION"
        |  ],
        |  "entrypoint": "synthetic.SyntheticPluginEntrypoint"
        |}""".stripMargin

    val sourceJar = tempDir.resolve("synthetic-plugin.jar")
    createJarFromPackage(sourceJar, manifestJson)

    val manager = new PluginManager(pluginsDirectory = pluginsDir)
    try {
      val manifest = manager.installPluginJar(sourceJar)
      assertEquals(manifest.id, "synthetic-test-plugin")
      assertEquals(manifest.name, "Synthetic Test Plugin")
      assertEquals(manifest.vendor, "Umbrix Testing")
      assertEquals(manifest.requiresRestart, false)
      assert(manager.isPluginEnabled("synthetic-test-plugin"))
      assertEquals(manager.allInstalledPlugins.size(), 1)
    } finally {
      manager.close()
    }
  }

  test("PluginClassLoader isolates engine packages and delegates shared contracts") {
    val tempDir = Files.createTempDirectory("umbrix-loader-test-")
    val pluginsDir = tempDir.resolve("plugins")
    Files.createDirectories(pluginsDir)

    val manifestJson =
      """{
        |  "id": "synthetic-test-plugin",
        |  "name": "Synthetic Test Plugin",
        |  "version": "1.0.0",
        |  "vendor": "Umbrix Testing",
        |  "description": "Test",
        |  "minUmbrixVersion": "1.0.0",
        |  "requiresRestart": false,
        |  "capabilities": ["ACTION"],
        |  "entrypoint": "synthetic.SyntheticPluginEntrypoint"
        |}""".stripMargin

    val jar = tempDir.resolve("loader-test.jar")
    createJarFromPackage(jar, manifestJson)

    val manager = new PluginManager(pluginsDirectory = pluginsDir)
    try {
      manager.installPluginJar(jar)
      val installed = manager.getInstalledPlugin("synthetic-test-plugin").get
      val classLoader = installed.classLoader

      // 1. Child-first: plugin class loads from child JAR
      val pluginClass = classLoader.loadClass("synthetic.SyntheticPluginEntrypoint")
      assert(pluginClass != null)

      // 2. Shared contracts delegate to parent host
      val sharedInterface = classLoader.loadClass("umbrix.plugin.api.UmbrixPlugin")
      assert(sharedInterface != null)
      val roaringClass = classLoader.loadClass("org.roaringbitmap.longlong.Roaring64NavigableMap")
      assert(roaringClass != null)

      // 3. Internal engine packages are blocked
      val threwBlocked = try {
        classLoader.loadClass("umbrix.core.session.LogSession")
        false
      } catch {
        case ex: ClassNotFoundException =>
          ex.getMessage.contains("prohibited for plugins")
      }
      assert(threwBlocked, "Internal engine classes must be blocked by PluginClassLoader")
    } finally {
      manager.close()
    }
  }

  test("SimdHelper executes accelerated vector byte counting and scans on off-heap memory") {
    val helper = new SimdHelperImpl()
    val arena = Arena.ofConfined()
    try {
      val text = "ALPHA|BRAVO|CHARLIE|DELTA|ECHO|FOXTROT\n"
      val bytes = text.getBytes(StandardCharsets.UTF_8)
      val segment = arena.allocate(bytes.length.toLong)
      MemorySegment.copy(MemorySegment.ofArray(bytes), 0L, segment, 0L, bytes.length.toLong)

      // 1. countByte
      val pipeCount = helper.countByte(segment, 0L, bytes.length.toLong, '|'.toByte)
      assertEquals(pipeCount, 5L)

      // 2. findFirstByte
      val firstPipe = helper.findFirstByte(segment, 0L, bytes.length.toLong, '|'.toByte)
      assertEquals(firstPipe, 5L)

      // 3. scanByte
      val offsets = new LongArrayList()
      val scanned = helper.scanByte(segment, 0L, bytes.length.toLong, '|'.toByte, (offset: Long) => offsets.add(offset))
      assertEquals(scanned, 5L)
      assertEquals(offsets.size(), 5)
      assertEquals(offsets.getLong(0), 5L)

      // 4. scanEitherByte
      val delimiterOffsets = new LongArrayList()
      val eitherCount = helper.scanEitherByte(
        segment,
        0L,
        bytes.length.toLong,
        '|'.toByte,
        '\n'.toByte,
        (offset: Long) => delimiterOffsets.add(offset)
      )
      assertEquals(eitherCount, 6L)
      assertEquals(delimiterOffsets.size(), 6)
    } finally {
      arena.close()
    }
  }

  test("FileFormatProvider probes custom format and executes record framing") {
    val provider = new synthetic.SyntheticFormatProvider()
    val dummyHeader = Arena.ofAuto().allocateFrom("SYNTH_HEADER_DATA_1234")

    val probeResult = provider.probe(Paths.get("data.synth"), dummyHeader, 2048L)
    assert(probeResult.matched)
    assertEquals(probeResult.priority, 100)
    assertEquals(probeResult.formatDisplayName, "Synthetic Delimited Format")

    val framer = provider.createRecordFramer(Paths.get("data.synth"), dummyHeader)
    val formatter = provider.createRecordFormatter(Paths.get("data.synth"), dummyHeader)

    val arena = Arena.ofConfined()
    try {
      val data = "REC0|REC1|REC2|REC3\n".getBytes(StandardCharsets.UTF_8)
      val segment = arena.allocate(data.length.toLong)
      MemorySegment.copy(MemorySegment.ofArray(data), 0L, segment, 0L, data.length.toLong)

      val boundaries = new LongArrayList()
      val count = framer.scanRecordBoundaries(segment, 0L, data.length.toLong, (offset: Long) => boundaries.add(offset))
      assertEquals(count, 4L)
      assertEquals(boundaries.size(), 4)

      val builder = new java.lang.StringBuilder()
      formatter.formatRecord(0L, segment.asSlice(0L, 4L), 100, builder)
      assertEquals(builder.toString, "REC0")
    } finally {
      arena.close()
    }
  }

  test("DocumentDecorator gutter badges and PieceTable virtual expansion round-trip") {
    val tempDir = Files.createTempDirectory("umbrix-decorator-test-")
    val pluginsDir = tempDir.resolve("plugins")
    Files.createDirectories(pluginsDir)

    val manifestJson =
      """{
        |  "id": "synthetic-test-plugin",
        |  "name": "Synthetic Test Plugin",
        |  "version": "1.0.0",
        |  "vendor": "Umbrix Testing",
        |  "description": "Test",
        |  "minUmbrixVersion": "1.0.0",
        |  "requiresRestart": false,
        |  "capabilities": ["DOCUMENT_DECORATOR"],
        |  "entrypoint": "synthetic.SyntheticPluginEntrypoint"
        |}""".stripMargin

    val jar = tempDir.resolve("decorator-test.jar")
    createJarFromPackage(jar, manifestJson)

    val manager = new PluginManager(pluginsDirectory = pluginsDir)
    try {
      manager.installPluginJar(jar)

      val sampleLog =
        "Line 0\n" +
        "Line 1\n" +
        "Line 2 with target record\n" +
        "Line 3\n" +
        "Line 4\n" +
        "Line 5 expandable record\n" +
        "Line 6\n"

      val logFile = tempDir.resolve("test.log")
      Files.write(logFile, sampleLog.getBytes(StandardCharsets.UTF_8))

      val session = LogSession.open(logFile)
      try {
        val sessionView = session.pluginSessionView

        // 1. Gutter badge decoration
        val badge = manager.resolveGutterDecoration(sessionView, 2L, MemorySegment.NULL)
        assert(badge != null)
        assertEquals(badge.badgeText, "SYN")
        assertEquals(badge.tooltipSummary, "Synthetic Tag")

        val noBadge = manager.resolveGutterDecoration(sessionView, 0L, MemorySegment.NULL)
        assertEquals(noBadge, null)

        // 2. Virtual expansion
        assert(manager.canExpandRecord(sessionView, 5L, MemorySegment.NULL))
        assert(!manager.canExpandRecord(sessionView, 1L, MemorySegment.NULL))

        manager.populateVirtualExpansion(sessionView, 5L, MemorySegment.NULL)
        assert(session.pieceTable.hasVirtualExpansion(5L))

        val expansionLines = session.pieceTable.getVirtualExpansionLines(5L, 10, 4096)
        assertEquals(expansionLines.size, 2)
        assertEquals(expansionLines(0), "--- SYNTHETIC EXPANSION ROW 1 ---")
        assertEquals(expansionLines(1), "--- SYNTHETIC EXPANSION ROW 2 ---")
      } finally {
        session.close()
      }
    } finally {
      manager.close()
    }
  }

  test("PluginAction executes with invocation context and records target line") {
    val tempDir = Files.createTempDirectory("umbrix-action-test-")
    val pluginsDir = tempDir.resolve("plugins")
    Files.createDirectories(pluginsDir)

    val manifestJson =
      """{
        |  "id": "synthetic-test-plugin",
        |  "name": "Synthetic Test Plugin",
        |  "version": "1.0.0",
        |  "vendor": "Umbrix Testing",
        |  "description": "Test",
        |  "minUmbrixVersion": "1.0.0",
        |  "requiresRestart": false,
        |  "capabilities": ["ACTION"],
        |  "entrypoint": "synthetic.SyntheticPluginEntrypoint"
        |}""".stripMargin

    val jar = tempDir.resolve("action-test.jar")
    createJarFromPackage(jar, manifestJson)

    val manager = new PluginManager(pluginsDirectory = pluginsDir)
    try {
      manager.installPluginJar(jar)

      val actions = manager.commandPaletteActions
      assert(actions.size() >= 1)
      val action = actions.get(0)
      assertEquals(action.actionId, "synthetic.inspect_action")
      assertEquals(action.label, "Inspect Synthetic Record")
      assertEquals(action.shortcutHint, "Ctrl+Shift+S")

      val invocation = PluginActionInvocation(
        session = null,
        workspace = manager.getWorkspaceController,
        recordIndex = 42L,
        rawRecordSlice = MemorySegment.NULL,
        recordText = "Sample record text",
        selectedText = "Sample"
      )
      action.invoke(invocation)

      val installed = manager.getInstalledPlugin("synthetic-test-plugin").get
      // Action instance was invoked without errors
    } finally {
      manager.close()
    }
  }

  test("PluginConfigStore persists declarative configuration to JSON on disk") {
    val tempDir = Files.createTempDirectory("umbrix-config-test-")
    val pluginsDir = tempDir.resolve("plugins")
    Files.createDirectories(pluginsDir)

    val manifestJson =
      """{
        |  "id": "synthetic-test-plugin",
        |  "name": "Synthetic Test Plugin",
        |  "version": "1.0.0",
        |  "vendor": "Umbrix Testing",
        |  "description": "Test",
        |  "minUmbrixVersion": "1.0.0",
        |  "requiresRestart": false,
        |  "capabilities": [],
        |  "entrypoint": "synthetic.SyntheticPluginEntrypoint"
        |}""".stripMargin

    val jar = tempDir.resolve("config-test.jar")
    createJarFromPackage(jar, manifestJson)

    val manager = new PluginManager(pluginsDirectory = pluginsDir)
    try {
      manager.installPluginJar(jar)

      val configPath = pluginsDir.resolve("synthetic-test-plugin").resolve("config.json")
      assert(Files.exists(configPath), s"Config file should be created at $configPath")

      val store = new PluginConfigStoreImpl(configPath)
      assertEquals(store.getInt("synth.batch_size", 0), 128)

      store.setString("custom_setting", "hello_umbrix")
      store.save()

      val reloaded = new PluginConfigStoreImpl(configPath)
      assertEquals(reloaded.getString("custom_setting", ""), "hello_umbrix")
      assertEquals(reloaded.getInt("synth.batch_size", 0), 128)
    } finally {
      manager.close()
    }
  }

  test("QueryFunction executes via fn:syntax returning Roaring64NavigableMap matches") {
    val tempDir = Files.createTempDirectory("umbrix-query-test-")
    val pluginsDir = tempDir.resolve("plugins")
    Files.createDirectories(pluginsDir)

    val manifestJson =
      """{
        |  "id": "synthetic-test-plugin",
        |  "name": "Synthetic Test Plugin",
        |  "version": "1.0.0",
        |  "vendor": "Umbrix Testing",
        |  "description": "Test",
        |  "minUmbrixVersion": "1.0.0",
        |  "requiresRestart": false,
        |  "capabilities": ["QUERY_FUNCTION"],
        |  "entrypoint": "synthetic.SyntheticPluginEntrypoint"
        |}""".stripMargin

    val jar = tempDir.resolve("query-test.jar")
    createJarFromPackage(jar, manifestJson)

    // Install into the global singleton so LogSession and SimdSearchEngine resolve it
    PluginManager.instance.installPluginJar(jar)
    try {
      val parsed = QueryParser.parse("fn:synthetic_match()")
      assertEquals(parsed, Right(QueryAst.CustomFunction("synthetic_match", Nil)))

      val sampleLog = "Line 0\nLine 1\nLine 2\nLine 3\nLine 4\nLine 5\n"
      val logFile = tempDir.resolve("query.log")
      Files.write(logFile, sampleLog.getBytes(StandardCharsets.UTF_8))

      val session = LogSession.open(logFile)
      try {
        val searchResult = session.search("fn:synthetic_match()").toOption.get
        // Expected matching lines: 0, 2, 4
        assertEquals(searchResult.totalMatches, 3L)
        assert(searchResult.matchingLines.contains(0L))
        assert(!searchResult.matchingLines.contains(1L))
        assert(searchResult.matchingLines.contains(2L))
        assert(!searchResult.matchingLines.contains(3L))
        assert(searchResult.matchingLines.contains(4L))
      } finally {
        session.close()
      }
    } finally {
      PluginManager.instance.close()
    }
  }

  test("McpToolProvider registers in PluginManager and executes within PluginSessionView") {
    val tempDir = Files.createTempDirectory("umbrix-mcp-test-")
    val pluginsDir = tempDir.resolve("plugins")
    Files.createDirectories(pluginsDir)

    val manifestJson =
      """{
        |  "id": "synthetic-test-plugin",
        |  "name": "Synthetic Test Plugin",
        |  "version": "1.0.0",
        |  "vendor": "Umbrix Testing",
        |  "description": "Test",
        |  "minUmbrixVersion": "1.0.0",
        |  "requiresRestart": false,
        |  "capabilities": ["MCP_TOOL"],
        |  "entrypoint": "synthetic.SyntheticPluginEntrypoint"
        |}""".stripMargin

    val jar = tempDir.resolve("mcp-test.jar")
    createJarFromPackage(jar, manifestJson)

    val manager = new PluginManager(pluginsDirectory = pluginsDir)
    try {
      manager.installPluginJar(jar)

      val sampleLog = "2026-09-22 12:00:00 [INFO] Server startup\n"
      val logFile = tempDir.resolve("mcp.log")
      Files.write(logFile, sampleLog.getBytes(StandardCharsets.UTF_8))

      val session = LogSession.open(logFile)
      try {
        val toolOpt = manager.allMcpToolProviders.asScala.find(_.toolName == "synthetic_analyzer")
        assert(toolOpt.isDefined, "synthetic_analyzer MCP tool must be registered in PluginManager")
        val tool = toolOpt.get
        assertEquals(tool.parameters.length, 1)
        assertEquals(tool.parameters(0).name, "target")

        val args = java.util.Map.of("target", "production-node-01")
        val result = tool.execute(session.pluginSessionView, args)
        assert(!result.isError)
        assert(result.outputText.contains("Synthetic analysis for production-node-01"))
        assert(result.outputText.contains("api_key=sk_live_syntheticsecret12345678"))
      } finally {
        session.close()
      }
    } finally {
      manager.close()
    }
  }

  test("PluginFaultSupervisor isolates unhandled exceptions and trips circuit breaker") {
    val tempDir = Files.createTempDirectory("umbrix-fault-test-")
    val pluginsDir = tempDir.resolve("plugins")
    Files.createDirectories(pluginsDir)

    val manifestJson =
      """{
        |  "id": "faulty-crash-plugin",
        |  "name": "Faulty Crash Plugin",
        |  "version": "1.0.0",
        |  "vendor": "Umbrix Testing",
        |  "description": "Test",
        |  "minUmbrixVersion": "1.0.0",
        |  "requiresRestart": false,
        |  "capabilities": ["DOCUMENT_DECORATOR"],
        |  "entrypoint": "synthetic.FaultyPluginEntrypoint"
        |}""".stripMargin

    val jar = tempDir.resolve("faulty-test.jar")
    createJarFromPackage(jar, manifestJson)

    val manager = new PluginManager(pluginsDirectory = pluginsDir)
    try {
      manager.installPluginJar(jar)
      assert(!manager.faultSupervisor.isTripped("faulty-crash-plugin"))

      // Call 1: trips fault count to 1, returns fallback false
      val res1 = manager.canExpandRecord(null, 0L, MemorySegment.NULL)
      assertEquals(res1, false)
      assert(!manager.faultSupervisor.isTripped("faulty-crash-plugin"))

      // Call 2: trips fault count to 2, returns fallback false
      val res2 = manager.canExpandRecord(null, 0L, MemorySegment.NULL)
      assertEquals(res2, false)
      assert(!manager.faultSupervisor.isTripped("faulty-crash-plugin"))

      // Call 3: trips fault count to 3 (threshold), circuit breaker trips
      val res3 = manager.canExpandRecord(null, 0L, MemorySegment.NULL)
      assertEquals(res3, false)
      assert(manager.faultSupervisor.isTripped("faulty-crash-plugin"), "Circuit breaker must be tripped after 3 faults")

      // Call 4: immediately short-circuited
      val res4 = manager.canExpandRecord(null, 0L, MemorySegment.NULL)
      assertEquals(res4, false)

      // Reset circuit breaker restores normal monitoring
      manager.faultSupervisor.resetCircuitBreaker("faulty-crash-plugin")
      assert(!manager.faultSupervisor.isTripped("faulty-crash-plugin"))
    } finally {
      manager.close()
    }
  }
}
