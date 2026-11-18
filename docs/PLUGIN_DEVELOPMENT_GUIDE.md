# Umbrix Plugin Design & Development Guide

> **Target Platform:** JDK 25 LTS (Azul Zulu 25.0.4.1+) | Scala 3.3.8 | Kotlin 2.1.10 + Compose Desktop 1.7.3 (Jewel/Skia)  
> **Published Artifacts:** `umbrix:umbrix-plugin-api:1.0.1` (Core SPI) & `umbrix:umbrix-plugin-ui-api:1.0.1` (UI SPI)

---

## Table of Contents
1. [Architectural Overview & Core Invariants](#1-architectural-overview--core-invariants)
2. [SDK Modules & Project Setup](#2-sdk-modules--project-setup)
3. [Plugin Manifest (`META-INF/umbrix-plugin.json`)](#3-plugin-manifest-meta-infumbrix-pluginjson)
4. [Plugin Entrypoint & Context Lifecycle](#4-plugin-entrypoint--context-lifecycle)
5. [SPI Extension Recipes](#5-spi-extension-recipes)
   - [5.1 Custom File Formats & Framed Record Indexing](#51-custom-file-formats--framed-record-indexing)
   - [5.2 Hardware-Accelerated SIMD Kernels](#52-hardware-accelerated-simd-kernels)
   - [5.3 Viewport Decorators & Off-Heap Virtual Expansions](#53-viewport-decorators--off-heap-virtual-expansions)
   - [5.4 Custom Dock Views (Compose Desktop & Skia GPU Canvas)](#54-custom-dock-views-compose-desktop--skia-gpu-canvas)
   - [5.5 Custom Search Bar Query Functions (`fn:`)](#55-custom-search-bar-query-functions-fn)
   - [5.6 Context Menu & Command Palette Actions](#56-context-menu--command-palette-actions)
   - [5.7 AI MCP Forensic Tools & DLP Governance](#57-ai-mcp-forensic-tools--dlp-governance)
   - [5.8 Declarative Settings & Configuration Store](#58-declarative-settings--configuration-store)
6. [Classloader Isolation & Security Model](#6-classloader-isolation--security-model)
7. [Fault Containment & Circuit Breaker](#7-fault-containment--circuit-breaker)
8. [Packaging, Distribution & Testing](#8-packaging-distribution--testing)

---

## 1. Architectural Overview & Core Invariants

Umbrix is a desktop log and forensic analysis platform for investigating outages and production issues across **20GB–100GB+ log files** with **<2s initial render**, **<150MB RSS**, and **0 bytes of raw log data on the JVM GC heap**.

The Extensibility SDK allows plugins (including first-party extensions such as the upcoming JVM diagnostic and heap dump plugin) to add custom file formats, dock views, search functions, and MCP tools while preserving those memory and latency invariants.

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Umbrix Host Application                         │
│  ┌───────────────────────┐  ┌──────────────────────────────────────┐  │
│  │     LogSession        │  │          PluginManager               │  │
│  │ (FFM OS Page Cache)   │  │   • JAR Discovery (~/.umbrix/plugins)│  │
│  │ (TwoLevelSparseIndex) │  │   • Arena Native Memory Tracking     │  │
│  │ (PieceTable Expansion)│  │   • PluginFaultSupervisor Circuit    │  │
│  └───────────┬───────────┘  └──────────────────┬───────────────────┘  │
└──────────────┼─────────────────────────────────┼──────────────────────┘
               │                                 │
     Off-Heap MemorySegment            Isolated PluginClassLoader
               │                                 │
┌──────────────▼─────────────────────────────────▼──────────────────────┐
│                        Third-Party Plugin JAR                          │
│  • META-INF/umbrix-plugin.json                                         │
│  • UmbrixPlugin Entrypoint (Scala 3, Kotlin, or Java)                  │
│  • Custom RecordFramer / Formatter (PCAP, HPROF, FIX, JSON Lines)      │
│  • DocumentDecorator (Gutter badges, inline chips, hover micro-cards)  │
│  • CustomDockView (Compose Desktop or direct Skia GPU Canvas)          │
│  • QueryFunction (fn:<name> returning Roaring64NavigableMap)           │
│  • McpToolProvider (Dynamic LLM tools with client-side DLP)            │
└────────────────────────────────────────────────────────────────────────┘
```

### Mandatory Plugin Invariants

1. **Zero-Heap Log Processing:** Never convert raw record buffers into `java.lang.String` or JVM objects during scanning or indexing. All log data lives strictly off-heap in `java.lang.foreign.MemorySegment`. Materialize `String` instances *only* for the ~50 lines currently visible on-screen in the UI viewport.
2. **Deterministic Native Lifecycles:** Use the plugin's dedicated `context.pluginArena` (or bounded short-lived arenas) for native memory. When a plugin is disabled or reloaded, all native memory allocated through its arena is closed deterministically.
3. **No Thread Starvation:** Long-running analyses, network calls, or intensive vector scans must run asynchronously on background threads (`CompletableFuture`, virtual threads, or `simdHelper.scanParallelChunks`). Never block the UI compositor thread.
4. **ReDoS Immunity:** Do not use `java.util.regex` across log search paths. Use linear-time regular expressions (`com.google.re2j`) or SIMD vector scanning (`context.simdHelper`).
5. **Circuit Breaker Awareness:** If your plugin throws unhandled exceptions during rendering or indexing, the host `PluginFaultSupervisor` will trap the failure, emit a structured forensic report, and trip the circuit breaker after 3 consecutive errors to prevent workstation crashes.

---

## 2. SDK Modules & Project Setup

The SDK is split into two modules published to Maven Local (`mavenLocal()`):

| Module | Language | Purpose | Maven Coordinate |
| :--- | :--- | :--- | :--- |
| **`:umbrix-plugin-api`** | Scala 3 / Java | Pure headless SPI: manifests, formats, SIMD, decorators, query AST, actions, MCP tools, config. | `umbrix:umbrix-plugin-api:1.0.1` |
| **`:umbrix-plugin-ui-api`** | Kotlin / Compose | Desktop UI SPI: `ComposeDockView`, `ComposePluginSettingsPanel`, theme tokens. | `umbrix:umbrix-plugin-ui-api:1.0.1` |

### Gradle Build Configuration (`build.gradle.kts`)

```kotlin
plugins {
    scala // or kotlin("jvm") version "2.1.10"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // Pure headless plugin:
    compileOnly("umbrix:umbrix-plugin-api:1.0.1")

    // UI-enabled plugin (Compose Desktop / Skia):
    compileOnly("umbrix:umbrix-plugin-ui-api:1.0.1")

    // Shared host dependencies (Provided at runtime by Umbrix Host - DO NOT SHADOW):
    compileOnly("org.roaringbitmap:RoaringBitmap:1.3.0")
    compileOnly("it.unimi.dsi:fastutil:8.5.15")
    compileOnly("com.google.re2j:re2j:1.8")
    compileOnly("org.slf4j:slf4j-api:2.0.16")

    // Third-party libraries unique to your plugin (e.g. Jackson, SQLite, Protobuf):
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}

// Bundle into a Fat JAR containing third-party dependencies
tasks.shadowJar {
    archiveClassifier.set("")
    // Ensure shared host packages are excluded from the fat JAR
    dependencies {
        exclude(dependency("umbrix:.*"))
        exclude(dependency("org.roaringbitmap:.*"))
        exclude(dependency("it.unimi.dsi:fastutil:.*"))
        exclude(dependency("org.slf4j:.*"))
    }
}
```

---

## 3. Plugin Manifest (`META-INF/umbrix-plugin.json`)

Every plugin JAR must contain a manifest located at `META-INF/umbrix-plugin.json`.

```json
{
  "id": "com.example.pcap-forensics",
  "name": "PCAP Network Packet Forensics",
  "version": "1.0.0",
  "vendor": "Security Research Labs",
  "description": "High-speed PCAP packet framing, protocol decoding, and conversation flow visualization.",
  "minUmbrixVersion": "1.0.0",
  "requiresRestart": false,
  "capabilities": [
    "FILE_FORMAT",
    "SIMD_ANALYZER",
    "DOCUMENT_DECORATOR",
    "DOCK_VIEW",
    "MCP_TOOL",
    "QUERY_FUNCTION",
    "ACTION"
  ],
  "entrypoint": "com.example.pcap.PcapPlugin"
}
```

### Manifest Fields Reference

| Field | Type | Description |
| :--- | :--- | :--- |
| `id` | `String` | Unique alphanumeric identifier using reverse domain notation (e.g. `org.company.plugin`). |
| `name` | `String` | Human-readable plugin name displayed in the Plugins Settings tab and title bars. |
| `version` | `String` | Semantic version string (e.g. `1.2.0`). |
| `vendor` | `String` | Developer, team, or organization name. |
| `description` | `String` | Concise summary of functionality. |
| `minUmbrixVersion` | `String` | Minimum compatible Umbrix workstation version (e.g. `1.0.0`). |
| `requiresRestart` | `Boolean` | `false` if hot reload/disable is safe; `true` if unloading requires a full app restart. |
| `capabilities` | `Array[String]` | Declared capability flags: `FILE_FORMAT`, `SIMD_ANALYZER`, `DOCUMENT_DECORATOR`, `DOCK_VIEW`, `MCP_TOOL`, `QUERY_FUNCTION`, `ACTION`. |
| `entrypoint` | `String` | Fully qualified class name implementing `umbrix.plugin.api.UmbrixPlugin`. |

---

## 4. Plugin Entrypoint & Context Lifecycle

Your entrypoint class must implement [`UmbrixPlugin`](../umbrix-plugin-api/src/main/scala/umbrix/plugin/api/UmbrixPlugin.scala).

```scala
package com.example.pcap

import umbrix.plugin.api.*
import umbrix.plugin.api.config.{PluginSettingDescriptor, PluginSettingType}

class PcapPlugin extends UmbrixPlugin {
  def id: String = "com.example.pcap-forensics"
  def name: String = "PCAP Network Forensics"

  def onEnable(context: PluginContext): Unit = {
    // 1. Declarative Settings Schema
    context.registerSettingsSchema(Array(
      PluginSettingDescriptor("pcap.dns_resolution", "Resolve IP Addresses", "Perform reverse DNS lookups", PluginSettingType.BOOLEAN, "false"),
      PluginSettingDescriptor("pcap.max_payload_bytes", "Max Payload Slice", "Bytes clamped for payload preview", PluginSettingType.INTEGER, "512")
    ))

    // 2. Extension Point Registrations
    context.registerFormatProvider(new PcapFormatProvider())
    context.registerDecorator(new PcapDecorator())
    context.registerQueryFunction(new IpFilterQueryFunction())
    context.registerAction(new ExportPcapStreamAction())
    context.registerMcpTool(new PcapAnalysisMcpTool())

    // 3. Optional Multi-Pane Dock View Factory
    context.registerDockView(new PacketStreamDockViewFactory())
  }

  override def onDisable(): Unit = {
    // Clean up long-lived thread pools or background sockets.
    // Native allocations bound to context.pluginArena are closed automatically by Umbrix.
  }
}
```

### Services Available on `PluginContext`

- `context.pluginId`: The unique ID of the plugin.
- `context.pluginDataDirectory`: Isolated persistent directory at `~/.umbrix/plugins/<pluginId>/data/`.
- `context.pluginArena`: Shared off-heap `java.lang.foreign.Arena` bound to the plugin lifecycle.
- `context.simdHelper`: Hardware-accelerated SIMD scanner utility (`VectorScanner` kernels).
- `context.configStore`: Thread-safe settings store backed by `~/.umbrix/plugins/<pluginId>/config.json`.
- `context.workspaceController`: Host workstation navigation and toast notification controller.

---

## 5. SPI Extension Recipes

### 5.1 Custom File Formats & Framed Record Indexing

Allows Umbrix to open non-newline or binary files (PCAP, HPROF heap dumps, FIX protocol, binary event logs) as high-speed 64-bit virtual documents.

```scala
package com.example.pcap

import java.lang.foreign.{MemorySegment, ValueLayout}
import java.nio.file.Path
import java.util.function.LongConsumer
import umbrix.plugin.api.format.*

class PcapFormatProvider extends FileFormatProvider {
  def id: String = "pcap-format"
  def displayName: String = "Wireshark PCAP Packet Capture"
  def supportedExtensions: Array[String] = Array(".pcap", ".cap", ".dmp")

  /**
   * Fast probe over the first 4KB of the file to determine if this provider handles it.
   */
  def probe(path: Path, headerSlice: MemorySegment, fileSizeBytes: Long): FormatProbeResult = {
    if (headerSlice != null && headerSlice.byteSize() >= 4L) {
      val magic = headerSlice.get(ValueLayout.JAVA_INT_UNALIGNED, 0L)
      if (magic == 0xa1b2c3d4 || magic == 0xd4c3b2a1) {
        // Matched! Priority 100 ensures higher precedence than default plaintext.
        // companionDockViewId automatically opens a custom companion visualization pane.
        FormatProbeResult(
          matched = true,
          priority = 100,
          formatDisplayName = displayName,
          openMode = FormatOpenMode.SPLIT_VIEWPORT_AND_DOCK_VIEW,
          companionDockViewId = "pcap.packet_inspector"
        )
      } else FormatProbeResult.NotMatched
    } else FormatProbeResult.NotMatched
  }

  /**
   * Identifies record boundaries across multi-megabyte chunks.
   */
  def createRecordFramer(path: Path, headerSlice: MemorySegment): RecordFramer = new RecordFramer {
    def scanRecordBoundaries(
      fileSegment: MemorySegment,
      startOffset: Long,
      length: Long,
      recordStartOffsetConsumer: LongConsumer
    ): Long = {
      var offset = startOffset
      val limit = startOffset + length
      var packetCount = 0L

      while (offset + 16L <= limit) {
        val capturedLen = fileSegment.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 8L) & 0xFFFFFFFFL
        val nextHeader = offset + 16L + capturedLen
        recordStartOffsetConsumer.accept(offset)
        packetCount += 1L
        offset = nextHeader
      }
      packetCount
    }

    def advanceRecordsFromCheckpoint(
      fileSegment: MemorySegment,
      fileSizeBytes: Long,
      checkpointByteOffset: Long,
      recordsToAdvance: Int
    ): Long = {
      var offset = checkpointByteOffset
      var advanced = 0
      while (offset + 16L <= fileSizeBytes && advanced < recordsToAdvance) {
        val capturedLen = fileSegment.get(ValueLayout.JAVA_INT_UNALIGNED, offset + 8L) & 0xFFFFFFFFL
        offset += 16L + capturedLen
        advanced += 1
      }
      offset
    }

    def recordByteLength(
      fileSegment: MemorySegment,
      fileSizeBytes: Long,
      recordByteOffset: Long
    ): Long = {
      if (recordByteOffset + 16L <= fileSizeBytes) {
        16L + (fileSegment.get(ValueLayout.JAVA_INT_UNALIGNED, recordByteOffset + 8L) & 0xFFFFFFFFL)
      } else 16L
    }
  }

  /**
   * Formats a raw record slice into single-line display text for viewport rows.
   */
  def createRecordFormatter(path: Path, headerSlice: MemorySegment): RecordFormatter = new RecordFormatter {
    def formatRecord(
      recordIndex: Long,
      rawRecordSlice: MemorySegment,
      maxChars: Int,
      targetBuilder: java.lang.StringBuilder
    ): Unit = {
      val epochSec = rawRecordSlice.get(ValueLayout.JAVA_INT_UNALIGNED, 0L) & 0xFFFFFFFFL
      val len = rawRecordSlice.get(ValueLayout.JAVA_INT_UNALIGNED, 8L) & 0xFFFFFFFFL
      targetBuilder
        .append(f"Packet #$recordIndex%06d | Epoch: $epochSec | Length: $len bytes | Protocol: IPv4")
    }
  }
}
```

---

### 5.2 Hardware-Accelerated SIMD Kernels

Plugins can leverage `SimdHelper` to scan raw off-heap segments using AVX-512, AVX2, or ARM NEON registers without writing native C code.

```scala
import java.lang.foreign.MemorySegment
import jdk.incubator.vector.ByteVector
import umbrix.plugin.api.simd.SimdHelper

class PacketPayloadScanner(simdHelper: SimdHelper) {

  def countDelimiters(segment: MemorySegment, offset: Long, length: Long): Long = {
    // Hardware accelerated byte counting (POPCNT)
    simdHelper.countByte(segment, offset, length, 0x00.toByte)
  }

  def scanCustomPattern(segment: MemorySegment, offset: Long, length: Long): Long = {
    // Aligned 3-stage ByteVector loop executing directly in hardware registers
    simdHelper.runAlignedVectorLoop(
      segment,
      offset,
      length,
      (vector: ByteVector, chunkOffset: Long) => {
        // Vector comparison: check for 0x7E (boundary flag)
        val mask = vector.eq(0x7E.toByte)
        if (mask.anyTrue()) {
          // Process matches
        }
      }
    )
  }
}
```

---

### 5.3 Viewport Decorators & Off-Heap Virtual Expansions

Allows plugins to enhance log lines with gutter icons, inline chip pills, 250ms hover micro-cards, and expand/collapse nested sub-rows without modifying the original log data.

```scala
package com.example.pcap

import java.lang.foreign.MemorySegment
import java.util.List as JList
import umbrix.plugin.api.PluginSessionView
import umbrix.plugin.api.decorator.*

class PcapDecorator extends DocumentDecorator {
  def id: String = "pcap.decorator"
  def displayName: String = "PCAP Decorator"

  // 1. Gutter Status Badges
  override def resolveGutterDecoration(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment
  ): GutterDecoration = {
    if (recordIndex % 100L == 0L) {
      GutterDecoration(badgeText = "SYN", colorArgb = 0xFF4CAF50, tooltipSummary = "TCP Handshake Start")
    } else null
  }

  // 2. Inline Chip Pills in Viewport Rows
  override def resolveInlineChips(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment,
    shapedRecordText: String,
    targetList: JList[InlineChipDecoration]
  ): Unit = {
    targetList.add(
      InlineChipDecoration(
        charOffset = 0,
        label = "TCP",
        textColorArgb = 0xFFFFFFFF,
        backgroundColorArgb = 0xFF1976D2
      )
    )
  }

  // 3. Hover Inspection Micro-Cards (250ms hover delay)
  override def resolveHoverCard(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment,
    shapedRecordText: String,
    charColumn: Int
  ): HoverCardDecoration = {
    HoverCardDecoration(
      title = s"Packet Frame #$recordIndex Details",
      subtitle = "Ethernet II / IPv4 / TCP",
      keyValuePairs = Array(
        "Source IP" -> "192.168.1.10:443",
        "Destination IP" -> "10.0.0.5:52314",
        "Window Size" -> "65535",
        "Checksum" -> "0xfa4b [Verified]"
      ),
      monospacePayload = "00 1a 2b 3c 4d 5e 08 00 45 00 00 3c"
    )
  }

  // 4. Line-Keyed Off-Heap Virtual Expansions
  override def canExpandRecord(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment
  ): Boolean = true

  override def populateVirtualExpansion(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment
  ): Unit = {
    // Writes decoded expansion lines into off-heap PieceTable 1MB Arena chunks
    val decodedPayload =
      """  ├─ Ethernet II, Src: 00:1a:2b:3c:4d:5e, Dst: 00:0c:29:8a:12:34
        |  ├─ Internet Protocol Version 4, Src: 192.168.1.10, Dst: 10.0.0.5
        |  └─ Transmission Control Protocol, Src Port: 443, Dst Port: 52314, Seq: 1""".stripMargin

    session.putVirtualExpansionText(recordIndex, decodedPayload)
  }
}
```

---

### 5.4 Custom Dock Views (Compose Desktop & Skia GPU Canvas)

Plugins can contribute docking tabs. Two approaches are supported:

#### Approach A: Jetpack Compose Desktop (`ComposeDockView`)

Written in Kotlin, ideal for interactive form controls, tree views, and search panels:

```kotlin
package com.example.pcap.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Text
import umbrix.plugin.api.ui.DockViewContext
import umbrix.plugin.ui.ComposeDockView

class PacketInspectorDockView : ComposeDockView {
    override fun viewId(): String = "pcap.packet_inspector"
    override fun title(): String = "Packet Protocol Inspector"

    @Composable
    override fun Content(context: DockViewContext) {
        val selectedRecord = context.selectedRecordIndex()
        Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            Text("Selected Packet: #$selectedRecord")
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn {
                item { Text("Protocol: TCP") }
                item { Text("Flags: [SYN, ACK]") }
            }
        }
    }
}
```

#### Approach B: Direct Skia GPU Canvas (`SkiaCanvasDockView`)

Written in Scala or Java, ideal for 60–120 FPS high-density rendering (packet hex dumps, connection matrices, flame graphs):

```scala
package com.example.pcap.ui

import umbrix.plugin.api.ui.{CustomDockView, DockViewContext, SkiaCanvasDockView, SkiaCanvasSurface}

class PacketHexDumpDockView extends SkiaCanvasDockView {
  def viewId: String = "pcap.hexdump"
  def title: String = "Raw Frame Hex Dump"

  def renderCanvas(surface: SkiaCanvasSurface, context: DockViewContext): Unit = {
    val selectedRecord = context.selectedRecordIndex
    surface.drawRect(0f, 0f, surface.widthPx, surface.heightPx, 0xFF1E1E1E)
    surface.drawText(s"RAW PACKET #$selectedRecord HEX STREAM", 10f, 25f, 13f, 0xFF00E5FF, monospace = true)
    
    // Draw 16 bytes per row with alternating column highlights
    var y = 50f
    for (row <- 0 until 16) {
      surface.drawText(f"00$row%02X:  45 00 00 3c 1c 46 40 00  40 06 b1 e6 c0 a8 01 0a", 10f, y, 11f, 0xFFE0E0E0, monospace = true)
      y += 18f
    }
  }

  override def onMouseClick(xPx: Float, yPx: Float, isSecondary: Boolean, context: DockViewContext): Unit = {
    context.requestRepaint()
  }
}
```

---

### 5.5 Custom Search Bar Query Functions (`fn:`)

Plugins can introduce specialized functions into the Umbrix search bar (e.g. `fn:ip(192.168.1.1) AND ERROR`).

```scala
package com.example.pcap

import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.plugin.api.PluginSessionView
import umbrix.plugin.api.query.QueryFunction

class IpFilterQueryFunction extends QueryFunction {
  def name: String = "ip"
  def description: String = "Filters packets matching target IPv4 address: fn:ip(192.168.1.1)"

  def evaluate(
    session: PluginSessionView,
    arguments: Array[String]
  ): Roaring64NavigableMap = {
    val targetIp = if (arguments.nonEmpty) arguments(0) else ""
    val matchingLines = new Roaring64NavigableMap()

    // Query bitmap construction: return matching physical record indices
    var record = 0L
    val total = session.totalRecords
    while (record < total) {
      if (record % 2L == 0L) { // Example matching logic
        matchingLines.add(record)
      }
      record += 1L
    }
    matchingLines
  }
}
```

---

### 5.6 Context Menu & Command Palette Actions

Actions appear in right-click row menus and the `Ctrl+Shift+P` / `Double Shift` Command Palette.

```scala
package com.example.pcap

import umbrix.plugin.api.PluginSessionView
import umbrix.plugin.api.action.*

class ExportPcapStreamAction extends PluginAction {
  def actionId: String = "pcap.export_stream"
  def label: String = "Follow & Export TCP Stream"
  override def iconGlyph: String = "🌊"
  override def shortcutHint: String = "Ctrl+Shift+T"
  override def location: PluginActionLocation = PluginActionLocation.BOTH

  override def isApplicable(
    session: PluginSessionView,
    recordIndex: Long,
    recordText: String,
    selectedText: String
  ): Boolean = {
    recordText.contains("TCP")
  }

  def invoke(invocation: PluginActionInvocation): Unit = {
    val record = invocation.recordIndex
    invocation.workspace.showToast(s"Exporting TCP stream for packet #$record...", "INFO")
    invocation.workspace.openScratchpadWithText(
      s"TCP Stream (Packet #$record)",
      s"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"status\":\"ok\"}"
    )
  }
}
```

---

### 5.7 AI MCP Forensic Tools & DLP Governance

Expose specialized forensic tools to connected AI coding agents (Claude, Cursor, Antigravity) via Umbrix’s embedded Model Context Protocol (MCP) server.

```scala
package com.example.pcap

import java.util.Map as JMap
import umbrix.plugin.api.PluginSessionView
import umbrix.plugin.api.mcp.*

class PcapAnalysisMcpTool extends McpToolProvider {
  def toolName: String = "analyze_packet_flow"
  def description: String = "Computes TCP round-trip latency and retransmission anomalies for a host IP"

  def parameters: Array[PluginMcpParam] = Array(
    PluginMcpParam("host_ip", "string", "Target IPv4 address to trace", required = true),
    PluginMcpParam("max_packets", "integer", "Max frames to evaluate (default: 5000)", required = false)
  )

  def execute(
    session: PluginSessionView,
    arguments: JMap[String, String]
  ): PluginMcpToolResult = {
    val targetIp = arguments.getOrDefault("host_ip", "127.0.0.1")
    
    // Note: Any credentials, tokens, or private keys emitted in outputText
    // are automatically sanitized by Umbrix's client-side DlpSanitizer prior to egress.
    PluginMcpToolResult(
      outputText = s"Analysis for $targetIp: 42 frames, 0 retransmissions, average RTT: 1.24ms."
    )
  }
}
```

---

### 5.8 Declarative Settings & Configuration Store

Declare configuration fields that Umbrix automatically renders in the `PluginsSettingsTab` UI and persists in `~/.umbrix/plugins/<pluginId>/config.json`.

```scala
// Registration in onEnable:
context.registerSettingsSchema(Array(
  PluginSettingDescriptor(
    key = "capture.buffer_mb",
    label = "Ring Buffer Size (MB)",
    description = "Off-heap circular buffer allocation size",
    settingType = PluginSettingType.INTEGER,
    defaultValue = "128"
  ),
  PluginSettingDescriptor(
    key = "capture.promiscuous",
    label = "Promiscuous Capture Mode",
    description = "Capture all interface packets",
    settingType = PluginSettingType.BOOLEAN,
    defaultValue = "true"
  ),
  PluginSettingDescriptor(
    key = "capture.interface",
    label = "Default Network Interface",
    description = "Ethernet or Wi-Fi device binding",
    settingType = PluginSettingType.CHOICE,
    defaultValue = "eth0",
    choices = Array("eth0", "wlan0", "lo")
  )
))

// Reading and writing at runtime:
val bufferSize = context.configStore.getInt("capture.buffer_mb", 128)
context.configStore.setString("capture.interface", "wlan0")
context.configStore.save()
```

---

## 6. Classloader Isolation & Security Model

Umbrix uses a custom child-first [`PluginClassLoader`](../umbrix-core/src/main/scala/umbrix/core/plugin/PluginClassLoader.scala) for each installed JAR.

### Classloading Precedence

1. **Child-First Resolution:** The classloader searches the plugin's own JAR first for bundled dependencies (e.g. Jackson, Arrow, Protobuf), preventing version collisions.
2. **Shared Platform Delegation:** Transparently delegates shared frameworks to the host classloader:
   - JDK Platform (`java.*`, `jdk.*`)
   - Language Runtimes (`scala.*`, `kotlin.*`)
   - UI & Graphics Frameworks (`androidx.compose.*`, `org.jetbrains.skia.*`, `org.jetbrains.jewel.*`)
   - High-Performance Primitives (`org.roaringbitmap.*`, `it.unimi.dsi.fastutil.*`)
   - Umbrix Public SPI (`umbrix.plugin.api.*`, `umbrix.plugin.ui.*`)
3. **Hardened Engine Isolation:** Prohibits plugins from loading internal workstation implementation classes:
   - `umbrix.core.*`
   - `umbrix.simd.*`
   - `umbrix.ui.*`
   - `umbrix.mcp.*`
   - `umbrix.app.*`
   
Attempting to load internal engine classes throws `ClassNotFoundException` with a security notice instructing the author to use the public SPI.

---

## 7. Fault Containment & Circuit Breaker

To guarantee that a faulty third-party plugin never crashes a critical workstation session:

1. **Guard Wrapper:** All extension invocations (framing, formatting, decorators, actions, MCP tools) run within [`PluginFaultSupervisor.executeGuarded`](../umbrix-core/src/main/scala/umbrix/core/plugin/PluginFaultSupervisor.scala).
2. **Automatic Diagnostics:** Any unhandled exception emits a structured forensic diagnostic report (`~/.umbrix/reports/error_report_*.json`).
3. **Circuit Breaker Trip:** If a plugin encounters **3 consecutive faults**, the supervisor trips the circuit breaker for that plugin. Subsequent calls short-circuit immediately to safe fallback values (`null`, `false`, or empty collections) with zero performance degradation on the UI thread.
4. **Manual Recovery:** Users can reset tripped circuit breakers or reload plugins with one click in the **`🧩 Plugins & Extensions`** settings tab.

---

## 8. Packaging, Distribution & Testing

### Deploying to Umbrix

1. Run Gradle `shadowJar` to build the fat JAR:
   ```bash
   ./gradlew shadowJar
   ```
2. Copy the resulting `.jar` file to the Umbrix plugins directory:
   - **Windows:** `C:\Users\<user>\.umbrix\plugins\`
   - **Linux/macOS:** `~/.umbrix/plugins/`
3. Alternatively, open Umbrix, navigate to **Settings (`Ctrl+,`) → Plugins & Extensions**, and click **"Install Plugin from Disk (.jar)..."**.

### Integration Testing with munit

You can test your plugin programmatically using the same integration pattern used by the core test suite:

```scala
package com.example.pcap

import munit.FunSuite
import java.nio.file.Files
import umbrix.core.plugin.PluginManager

class PcapPluginTest extends FunSuite {

  test("PcapPlugin loads and registers format provider") {
    val tempDir = Files.createTempDirectory("umbrix-test-plugins")
    val manager = new PluginManager(pluginsDirectory = tempDir)
    try {
      // Install and verify manifest
      val manifest = manager.installPluginJar(myPluginJarPath)
      assertEquals(manifest.id, "com.example.pcap-forensics")
      assert(manager.isPluginEnabled(manifest.id))
      
      // Verify format probe
      val probe = manager.probeFormat(java.nio.file.Path.of("test.pcap"), sampleHeaderSegment, 1024L)
      assert(probe.matched)
    } finally {
      manager.close()
    }
  }
}
```
