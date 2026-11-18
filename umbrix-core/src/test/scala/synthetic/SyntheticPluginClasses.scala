package synthetic

import java.lang.foreign.{MemorySegment, ValueLayout}
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.function.LongConsumer
import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.plugin.api.*
import umbrix.plugin.api.action.{PluginAction, PluginActionInvocation, PluginActionLocation}
import umbrix.plugin.api.config.{PluginConfigStore, PluginSettingDescriptor, PluginSettingType}
import umbrix.plugin.api.decorator.{DocumentDecorator, GutterDecoration, HoverCardDecoration, InlineChipDecoration}
import umbrix.plugin.api.format.{FileFormatProvider, FormatProbeResult, RecordFramer, RecordFormatter}
import umbrix.plugin.api.mcp.{McpToolProvider, PluginMcpParam, PluginMcpToolResult}
import umbrix.plugin.api.query.QueryFunction

class SyntheticPluginEntrypoint extends UmbrixPlugin {
  def id: String = "synthetic-test-plugin"
  def name: String = "Synthetic Test Plugin"

  def onEnable(context: PluginContext): Unit = {
    context.registerFormatProvider(new SyntheticFormatProvider())
    context.registerDecorator(new SyntheticDecorator())
    context.registerQueryFunction(new SyntheticQueryFunction())
    context.registerAction(new SyntheticAction())
    context.registerMcpTool(new SyntheticMcpTool())
    context.registerSettingsSchema(Array(
      PluginSettingDescriptor("synth.batch_size", "Batch Size", "Record batch size", PluginSettingType.INTEGER, "64"),
      PluginSettingDescriptor("synth.enabled", "Enabled", "Active state", PluginSettingType.BOOLEAN, "true")
    ))
    context.configStore.setInt("synth.batch_size", 128)
    context.configStore.save()
  }

  override def onDisable(): Unit = {}
}

class SyntheticFormatProvider extends FileFormatProvider {
  def id: String = "synthetic-format"
  def displayName: String = "Synthetic Delimited Format"
  def supportedExtensions: Array[String] = Array(".synth")

  def probe(path: Path, headerSlice: MemorySegment, fileSizeBytes: Long): FormatProbeResult = {
    val pathStr = if (path != null) path.getFileName.toString else ""
    if (pathStr.endsWith(".synth") || (headerSlice != null && headerSlice.byteSize() >= 4L && headerSlice.get(ValueLayout.JAVA_BYTE, 0L) == 'S'.toByte)) {
      FormatProbeResult(true, 100, displayName)
    } else {
      FormatProbeResult.NotMatched
    }
  }

  def createRecordFramer(path: Path, headerSlice: MemorySegment): RecordFramer = new RecordFramer {
    def scanRecordBoundaries(
      fileSegment: MemorySegment,
      startOffset: Long,
      length: Long,
      recordStartOffsetConsumer: LongConsumer
    ): Long = {
      var offset = startOffset
      val limit = startOffset + length
      var count = 0L
      while (offset < limit) {
        val b = fileSegment.get(ValueLayout.JAVA_BYTE, offset)
        if (b == '|'.toByte || b == 0x0A.toByte) {
          recordStartOffsetConsumer.accept(offset)
          count += 1L
        }
        offset += 1L
      }
      count
    }

    def advanceRecordsFromCheckpoint(
      fileSegment: MemorySegment,
      fileSizeBytes: Long,
      checkpointByteOffset: Long,
      recordsToAdvance: Int
    ): Long = {
      var offset = checkpointByteOffset
      var advanced = 0
      while (offset < fileSizeBytes && advanced < recordsToAdvance) {
        val b = fileSegment.get(ValueLayout.JAVA_BYTE, offset)
        if (b == '|'.toByte || b == 0x0A.toByte) {
          advanced += 1
        }
        offset += 1L
      }
      offset
    }

    def recordByteLength(
      fileSegment: MemorySegment,
      fileSizeBytes: Long,
      recordByteOffset: Long
    ): Long = {
      var offset = recordByteOffset
      while (offset < fileSizeBytes && fileSegment.get(ValueLayout.JAVA_BYTE, offset) != '|'.toByte && fileSegment.get(ValueLayout.JAVA_BYTE, offset) != 0x0A.toByte) {
        offset += 1L
      }
      (offset - recordByteOffset).max(1L)
    }
  }

  def createRecordFormatter(path: Path, headerSlice: MemorySegment): RecordFormatter = new RecordFormatter {
    def formatRecord(
      recordIndex: Long,
      rawRecordSlice: MemorySegment,
      maxChars: Int,
      targetBuilder: java.lang.StringBuilder
    ): Unit = {
      val size = Math.min(rawRecordSlice.byteSize().toInt, maxChars)
      val bytes = new Array[Byte](size)
      MemorySegment.copy(rawRecordSlice, ValueLayout.JAVA_BYTE, 0L, bytes, 0, size)
      targetBuilder.append(new String(bytes, StandardCharsets.UTF_8).trim)
    }
  }
}

class SyntheticDecorator extends DocumentDecorator {
  def id: String = "synthetic-decorator"
  def displayName: String = "Synthetic Decorator"

  override def resolveGutterDecoration(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment
  ): GutterDecoration = {
    if (recordIndex == 2L) {
      GutterDecoration("SYN", 0xFF00FF00, "Synthetic Tag")
    } else {
      null
    }
  }

  override def canExpandRecord(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment
  ): Boolean = {
    recordIndex == 5L
  }

  override def populateVirtualExpansion(
    session: PluginSessionView,
    recordIndex: Long,
    rawRecordSlice: MemorySegment
  ): Unit = {
    if (recordIndex == 5L) {
      session.putVirtualExpansionText(
        recordIndex,
        "--- SYNTHETIC EXPANSION ROW 1 ---\n--- SYNTHETIC EXPANSION ROW 2 ---"
      )
    }
  }
}

class SyntheticQueryFunction extends QueryFunction {
  def name: String = "synthetic_match"
  def description: String = "Matches even line numbers"

  def evaluate(
    session: PluginSessionView,
    arguments: Array[String]
  ): Roaring64NavigableMap = {
    val bitmap = new Roaring64NavigableMap()
    val total = if (session != null) session.totalRecords else 10L
    var line = 0L
    while (line < total) {
      if (line % 2L == 0L) {
        bitmap.add(line)
      }
      line += 1L
    }
    bitmap
  }
}

class SyntheticAction extends PluginAction {
  def actionId: String = "synthetic.inspect_action"
  def label: String = "Inspect Synthetic Record"
  override def shortcutHint: String = "Ctrl+Shift+S"
  override def location: PluginActionLocation = PluginActionLocation.BOTH

  var lastInvokedRecordIndex: Long = -1L

  def invoke(invocation: PluginActionInvocation): Unit = {
    lastInvokedRecordIndex = invocation.recordIndex
  }
}

class SyntheticMcpTool extends McpToolProvider {
  def toolName: String = "synthetic_analyzer"
  def description: String = "Analyzes synthetic records and yields sensitive tokens for DLP test"
  def parameters: Array[PluginMcpParam] = Array(
    PluginMcpParam("target", "string", "Target identifier", required = true)
  )

  def execute(
    session: PluginSessionView,
    arguments: java.util.Map[String, String]
  ): PluginMcpToolResult = {
    val target = arguments.getOrDefault("target", "unknown")
    PluginMcpToolResult(
      s"Synthetic analysis for $target: api_key=sk_live_syntheticsecret12345678 and token=ghp_abcdefghijklmnopqrstuvwxyz012345"
    )
  }
}

class FaultyPluginEntrypoint extends UmbrixPlugin {
  def id: String = "faulty-crash-plugin"
  def name: String = "Faulty Crash Plugin"

  def onEnable(context: PluginContext): Unit = {
    context.registerDecorator(new DocumentDecorator {
      def id: String = "faulty-decorator"
      def displayName: String = "Faulty Decorator"

      override def canExpandRecord(session: PluginSessionView, recordIndex: Long, rawRecordSlice: MemorySegment): Boolean = {
        throw new RuntimeException("Simulated unhandled decorator crash in plugin")
      }
    })
  }

  override def onDisable(): Unit = {}
}
