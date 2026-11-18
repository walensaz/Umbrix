package umbrix.mcp.tools

import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.collection.mutable
import umbrix.core.session.LogSession
import umbrix.core.types.{EpochMicros, LineIndex}
import umbrix.mcp.dlp.DlpSanitizer
import umbrix.mcp.protocol.*
import scala.jdk.CollectionConverters.*

/**
 * Registry and execution coordinator for high-performance forensic MCP tools.
 * Directly interfaces with the off-heap zero-GC core engine.
 */
class ForensicToolRegistry(sessionProvider: () => Option[LogSession]) {

  val builtInDefinitions: List[McpToolDefinition] = List(
    McpToolDefinition(
      name = "search_logs",
      description = "Performs high-speed SIMD vector search over multi-gigabyte logs. Supports literals, RE2 regex, and optional time windows. Returns matching line numbers, timestamps, and line content.",
      inputSchema = McpToolInputSchema(
        properties = Map(
          "query" -> McpToolParamProperty(`type` = "string", description = "Search query or regex expression"),
          "start_time" -> McpToolParamProperty(`type` = "string", description = "Optional ISO-8601 start timestamp filter"),
          "end_time" -> McpToolParamProperty(`type` = "string", description = "Optional ISO-8601 end timestamp filter"),
          "max_results" -> McpToolParamProperty(`type` = "integer", description = "Maximum number of matches to return (default: 50)")
        ),
        required = List("query")
      )
    ),
    McpToolDefinition(
      name = "get_time_slice",
      description = "Fetches a slice of log records between two timestamps via O(log N) binary seeking without reading the entire document.",
      inputSchema = McpToolInputSchema(
        properties = Map(
          "start_time" -> McpToolParamProperty(`type` = "string", description = "Start timestamp (ISO-8601 or HH:mm:ss)"),
          "end_time" -> McpToolParamProperty(`type` = "string", description = "End timestamp (ISO-8601 or HH:mm:ss)"),
          "limit" -> McpToolParamProperty(`type` = "integer", description = "Max rows to return (default: 50)")
        ),
        required = List("start_time", "end_time")
      )
    ),
    McpToolDefinition(
      name = "extract_causal_context",
      description = "Isolates the unbroken causal thread of execution around an error or transaction by scanning lines around a target line.",
      inputSchema = McpToolInputSchema(
        properties = Map(
          "target_line" -> McpToolParamProperty(`type` = "integer", description = "Target line number where error occurred"),
          "backward_lines" -> McpToolParamProperty(`type` = "integer", description = "Number of preceding lines to inspect (default: 20)"),
          "forward_lines" -> McpToolParamProperty(`type` = "integer", description = "Number of subsequent lines to inspect (default: 10)")
        ),
        required = List("target_line")
      )
    ),
    McpToolDefinition(
      name = "get_error_clusters",
      description = "Groups exception lines into canonical root-cause clusters with frequencies and sample representative lines.",
      inputSchema = McpToolInputSchema(
        properties = Map(
          "max_clusters" -> McpToolParamProperty(`type` = "integer", description = "Max top clusters to return (default: 5)")
        ),
        required = Nil
      )
    ),
    McpToolDefinition(
      name = "trim_and_export",
      description = "Extracts an exact subset of the log to disk via zero-copy DMA slicing.",
      inputSchema = McpToolInputSchema(
        properties = Map(
          "output_path" -> McpToolParamProperty(`type` = "string", description = "Target output file path"),
          "start_line" -> McpToolParamProperty(`type` = "integer", description = "Start line index"),
          "end_line" -> McpToolParamProperty(`type` = "integer", description = "End line index")
        ),
        required = List("output_path", "start_line", "end_line")
      )
    )
  )

  def definitions: List[McpToolDefinition] = {
    val pluginDefs = umbrix.core.plugin.PluginManager.instance.allMcpToolProviders.asScala.map { p =>
      McpToolDefinition(
        name = p.toolName,
        description = p.description,
        inputSchema = McpToolInputSchema(
          properties = p.parameters.map(param => param.name -> McpToolParamProperty(`type` = param.paramType, description = param.description)).toMap,
          required = p.parameters.filter(_.required).map(_.name).toList
        )
      )
    }
    builtInDefinitions ++ pluginDefs
  }

  def executeTool(name: String, args: java.util.Map[String, String]): McpResult = {
    import scala.jdk.CollectionConverters.*
    executeTool(name, args.asScala.toMap)
  }

  def executeTool(name: String, args: Map[String, String]): McpResult = {
    sessionProvider() match {
      case None =>
        McpResult(
          content = Some(List(McpContentItem(text = "Error: No active log session is currently open in Umbrix."))),
          isError = true
        )
      case Some(session) =>
        name match {
          case "search_logs" =>
            handleSearchLogs(session, args)
          case "get_time_slice" =>
            handleGetTimeSlice(session, args)
          case "extract_causal_context" =>
            handleExtractCausalContext(session, args)
          case "get_error_clusters" =>
            handleGetErrorClusters(session, args)
          case "trim_and_export" =>
            handleTrimAndExport(session, args)
          case unknown =>
            umbrix.core.plugin.PluginManager.instance.allMcpToolProviders.asScala.find(_.toolName == unknown) match {
              case Some(pluginTool) =>
                executePluginTool(pluginTool, session, args)
              case None =>
                McpResult(
                  content = Some(List(McpContentItem(text = s"Unknown MCP tool: $unknown"))),
                  isError = true
                )
            }
        }
    }
  }

  private def executePluginTool(
    tool: umbrix.plugin.api.mcp.McpToolProvider,
    session: LogSession,
    args: Map[String, String]
  ): McpResult = {
    import scala.jdk.CollectionConverters.*
    val timeoutSeconds = 15L
    val future = java.util.concurrent.CompletableFuture.supplyAsync(() => {
      tool.execute(session.pluginSessionView, args.asJava)
    })

    try {
      val rawResult = future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
      val sanitized = DlpSanitizer.sanitize(rawResult.outputText)
      McpResult(
        content = Some(List(McpContentItem(text = sanitized))),
        isError = rawResult.isError
      )
    } catch {
      case _: java.util.concurrent.TimeoutException =>
        future.cancel(true)
        McpResult(
          content = Some(List(McpContentItem(text = s"Plugin tool '${tool.toolName}' timed out after ${timeoutSeconds}s"))),
          isError = true
        )
      case t: Throwable =>
        val cause = if (t.getCause != null) t.getCause else t
        McpResult(
          content = Some(List(McpContentItem(text = s"Plugin tool '${tool.toolName}' failed: ${cause.getMessage}"))),
          isError = true
        )
    }
  }

  private def handleSearchLogs(session: LogSession, args: Map[String, String]): McpResult = {
    val query = args.getOrElse("query", "")
    val maxResults = args.get("max_results").flatMap(_.toIntOption).getOrElse(50).max(1).min(500)

    session.search(query) match {
      case Left(parseErr) =>
        McpResult(
          content = Some(List(McpContentItem(text = s"Search query syntax error: ${parseErr.message}"))),
          isError = true
        )
      case Right(searchResult) =>
        val matchCount = searchResult.totalMatches
        if (matchCount == 0L) {
          McpResult(
            content = Some(List(McpContentItem(text = s"No matches found for query: '$query' across ${session.totalLines.value} lines.")))
          )
        } else {
          val sb = new StringBuilder()
          sb.append(s"Found $matchCount matches for '$query'. Showing first ${math.min(matchCount, maxResults)} matches:\n\n")

          val iterator = searchResult.matchingLines.iterator()
          var count = 0
          while (iterator.hasNext && count < maxResults) {
            val lineIdx = iterator.next()
            val text = session.getLineText(LineIndex(lineIdx), 2048)
            val sanitized = DlpSanitizer.sanitize(text)
            sb.append(s"[Line $lineIdx] $sanitized\n")
            count += 1
          }

          if (matchCount > maxResults) {
            sb.append(s"\n... and ${matchCount - maxResults} more matches in ${session.fileName}.")
          }

          McpResult(content = Some(List(McpContentItem(text = sb.toString()))))
        }
    }
  }

  private def handleGetTimeSlice(session: LogSession, args: Map[String, String]): McpResult = {
    val startTimeStr = args.getOrElse("start_time", "")
    val endTimeStr = args.getOrElse("end_time", "")
    val limit = args.get("limit").flatMap(_.toIntOption).getOrElse(50).max(1).min(200)

    val startMicrosOpt = parseTimestampToMicros(session, startTimeStr)
    val endMicrosOpt = parseTimestampToMicros(session, endTimeStr)

    (startMicrosOpt, endMicrosOpt) match {
      case (Some(startMicros), Some(endMicros)) =>
        val bounds = session.seekLineRangeBounds(startMicros, endMicros)
        val startLine = bounds(0)
        val endLine = bounds(1)
        val totalInRange = (endLine - startLine + 1L).max(0L)

        val sb = new StringBuilder()
        sb.append(s"Time slice [$startTimeStr to $endTimeStr] spans lines $startLine to $endLine ($totalInRange lines total).\n")
        val linesToFetch = math.min(totalInRange, limit.toLong)
        var i = 0L
        while (i < linesToFetch) {
          val currentLine = startLine + i
          val text = session.getLineText(LineIndex(currentLine), 2048)
          sb.append(s"[Line $currentLine] ${DlpSanitizer.sanitize(text)}\n")
          i += 1L
        }

        if (totalInRange > linesToFetch) {
          sb.append(s"\n... and ${totalInRange - linesToFetch} more lines in this time window.")
        }

        McpResult(content = Some(List(McpContentItem(text = sb.toString()))))

      case _ =>
        McpResult(
          content = Some(List(McpContentItem(text = s"Could not parse timestamps: start='$startTimeStr', end='$endTimeStr'"))),
          isError = true
        )
    }
  }

  private def handleExtractCausalContext(session: LogSession, args: Map[String, String]): McpResult = {
    val targetLineOpt = args.get("target_line").flatMap(_.toLongOption)
    val backwardLines = args.get("backward_lines").flatMap(_.toIntOption).getOrElse(20).max(1).min(100)
    val forwardLines = args.get("forward_lines").flatMap(_.toIntOption).getOrElse(10).max(0).min(50)

    targetLineOpt match {
      case None =>
        McpResult(content = Some(List(McpContentItem(text = "Missing required argument 'target_line'"))), isError = true)
      case Some(targetLine) =>
        val startLine = math.max(0L, targetLine - backwardLines)
        val endLine = math.min(session.totalLines.value - 1L, targetLine + forwardLines)

        val sb = new StringBuilder()
        sb.append(s"Causal context surrounding Line $targetLine (Lines $startLine to $endLine):\n\n")

        var cur = startLine
        while (cur <= endLine) {
          val prefix = if (cur == targetLine) ">> [TARGET] " else "   "
          val text = session.getLineText(LineIndex(cur), 2048)
          sb.append(s"$prefix[Line $cur] ${DlpSanitizer.sanitize(text)}\n")
          cur += 1L
        }

        McpResult(content = Some(List(McpContentItem(text = sb.toString()))))
    }
  }

  private def handleGetErrorClusters(session: LogSession, args: Map[String, String]): McpResult = {
    val maxClusters = args.get("max_clusters").flatMap(_.toIntOption).getOrElse(5).max(1).min(20)

    session.search("ERROR OR Exception OR FATAL") match {
      case Left(_) =>
        McpResult(content = Some(List(McpContentItem(text = "Error scanning for error clusters"))), isError = true)
      case Right(searchResult) =>
        val totalErrors = searchResult.totalMatches
        if (totalErrors == 0L) {
          McpResult(content = Some(List(McpContentItem(text = "No ERROR or Exception occurrences found in document."))))
        } else {
          val clusters = mutable.LinkedHashMap[String, (Int, Long, String)]()
          val it = searchResult.matchingLines.iterator()
          var scanned = 0
          while (it.hasNext && scanned < 2000) {
            val lineIdx: Long = it.next().toLong
            val text = session.getLineText(LineIndex(lineIdx), 512)
            val normalizedKey = normalizeExceptionSignature(text)
            val current: (Int, Long, String) = clusters.getOrElse(normalizedKey, (0, lineIdx, text))
            clusters.put(normalizedKey, (current._1 + 1, current._2, current._3))
            scanned += 1
          }

          val topClusters = clusters.values.toList.sortBy(-_._1).take(maxClusters)
          val sb = new StringBuilder()
          sb.append(s"Analyzed $totalErrors error/exception occurrences. Top ${topClusters.size} root-cause clusters:\n\n")

          topClusters.zipWithIndex.foreach { case ((count, sampleLine, sampleText), idx) =>
            sb.append(s"### Cluster #${idx + 1} ($count occurrences, ~${(count * 100.0 / scanned).toInt}% of sample)\n")
            sb.append(s"- Representative: Line $sampleLine\n")
            sb.append(s"- Signature: ${DlpSanitizer.sanitize(sampleText.take(160))}\n\n")
          }

          McpResult(content = Some(List(McpContentItem(text = sb.toString()))))
        }
    }
  }

  private def handleTrimAndExport(session: LogSession, args: Map[String, String]): McpResult = {
    val outputPathStr = args.getOrElse("output_path", "")
    val startLineOpt = args.get("start_line").flatMap(_.toLongOption)
    val endLineOpt = args.get("end_line").flatMap(_.toLongOption)

    (startLineOpt, endLineOpt) match {
      case (Some(startLine), Some(endLine)) if outputPathStr.nonEmpty =>
        try {
          val destPath = Path.of(outputPathStr)
          val sliceResult = session.exportContiguousRange(LineIndex(startLine), LineIndex(endLine), destPath)
          McpResult(
            content = Some(List(McpContentItem(
              text = s"Successfully exported lines $startLine to $endLine (${sliceResult.bytesWritten} bytes written) to $destPath"
            )))
          )
        } catch {
          case ex: Throwable =>
            McpResult(content = Some(List(McpContentItem(text = s"Failed to export sub-file: ${ex.getMessage}"))), isError = true)
        }
      case _ =>
        McpResult(content = Some(List(McpContentItem(text = "Invalid arguments for trim_and_export"))), isError = true)
    }
  }

  private def normalizeExceptionSignature(line: String): String = {
    line
      .replaceAll("[0-9a-fA-F]{8,}", "<HEX>")
      .replaceAll("\\d+", "<N>")
      .take(80)
      .trim
  }

  private def parseTimestampToMicros(session: LogSession, input: String): Option[Long] = {
    val trimmed = input.trim
    if (trimmed.isEmpty) {
      None
    } else {
      try {
        val instant = Instant.parse(trimmed)
        Some(instant.getEpochSecond * 1000000L + instant.getNano / 1000L)
      } catch {
        case _: Throwable =>
          session.firstTimestamp match {
            case Some(firstMicros) =>
              try {
                val parts = trimmed.split(":")
                if (parts.length >= 2) {
                  val h = parts(0).toInt
                  val m = parts(1).toInt
                  val s = if (parts.length > 2) parts(2).toDouble else 0.0
                  val microsInDay = ((h * 3600L + m * 60L + s.toLong) * 1000000L) + ((s - s.toLong) * 1000000L).toLong
                  val dayBase = (firstMicros / 86400000000L) * 86400000000L
                  Some(dayBase + microsInDay)
                } else {
                  None
                }
              } catch {
                case _: Throwable => None
              }
            case None => None
          }
      }
    }
  }
}
