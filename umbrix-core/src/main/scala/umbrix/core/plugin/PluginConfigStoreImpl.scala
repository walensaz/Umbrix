package umbrix.core.plugin

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import umbrix.plugin.api.config.PluginConfigStore

/**
 * Thread-safe persistent configuration store backed by `~/.umbrix/plugins/<pluginId>/config.json`.
 */
class PluginConfigStoreImpl(val configFile: Path) extends PluginConfigStore {

  private val entries = new ConcurrentHashMap[String, String]()
  load()

  def getString(key: String, defaultValue: String = ""): String = {
    val value = entries.get(key)
    if (value != null) value else defaultValue
  }

  def getInt(key: String, defaultValue: Int = 0): Int = {
    val value = entries.get(key)
    if (value != null) {
      try {
        value.toInt
      } catch {
        case _: NumberFormatException => defaultValue
      }
    } else {
      defaultValue
    }
  }

  def getBoolean(key: String, defaultValue: Boolean = false): Boolean = {
    val value = entries.get(key)
    if (value != null) {
      value.equalsIgnoreCase("true")
    } else {
      defaultValue
    }
  }

  def setString(key: String, value: String): Unit = {
    entries.put(key, value)
  }

  def setInt(key: String, value: Int): Unit = {
    entries.put(key, value.toString)
  }

  def setBoolean(key: String, value: Boolean): Unit = {
    entries.put(key, value.toString)
  }

  def save(): Unit = {
    val parent = configFile.getParent
    if (parent != null && !Files.exists(parent)) {
      Files.createDirectories(parent)
    }

    val sb = new java.lang.StringBuilder("{\n")
    val list = entries.entrySet().asScala.toList.sortBy(_.getKey)
    var i = 0
    while (i < list.size) {
      val entry = list(i)
      val escapedKey = escapeJson(entry.getKey)
      val escapedVal = escapeJson(entry.getValue)
      sb.append("  \"").append(escapedKey).append("\": \"").append(escapedVal).append("\"")
      if (i < list.size - 1) {
        sb.append(",")
      }
      sb.append("\n")
      i += 1
    }
    sb.append("}\n")

    val tempFile = configFile.resolveSibling(configFile.getFileName.toString + ".tmp")
    Files.write(tempFile, sb.toString.getBytes(StandardCharsets.UTF_8))
    Files.move(tempFile, configFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
  }

  private def load(): Unit = {
    if (Files.exists(configFile)) {
      try {
        val lines = Files.readAllLines(configFile, StandardCharsets.UTF_8).asScala
        val pattern = "\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"".r
        for (line <- lines) {
          pattern.findFirstMatchIn(line).foreach { m =>
            val key = unescapeJson(m.group(1))
            val value = unescapeJson(m.group(2))
            entries.put(key, value)
          }
        }
      } catch {
        case _: Exception => // ignore corrupted config on initial load
      }
    }
  }

  private def escapeJson(s: String): String = {
    s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
  }

  private def unescapeJson(s: String): String = {
    s.replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
  }
}
