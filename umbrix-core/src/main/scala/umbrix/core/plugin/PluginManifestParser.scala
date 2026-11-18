package umbrix.core.plugin

import java.io.{BufferedReader, InputStream, InputStreamReader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.jar.JarFile
import com.google.re2j.Pattern
import umbrix.plugin.api.{PluginCapability, PluginManifest}

/**
 * Zero-dependency JSON parser for `META-INF/umbrix-plugin.json`.
 *
 * Extracts metadata, capability enum flags, and restart policy without pulling
 * third-party reflection or parsing engines into the core classloader.
 */
object PluginManifestParser {

  private final val ManifestPath = "META-INF/umbrix-plugin.json"

  /**
   * Reads and parses `META-INF/umbrix-plugin.json` from a plugin JAR file on disk.
   */
  def parseFromJar(jarPath: Path): PluginManifest = {
    if (!Files.exists(jarPath)) {
      throw new IllegalArgumentException(s"Plugin JAR file not found at: ${jarPath.toAbsolutePath}")
    }
    val jarFile = new JarFile(jarPath.toFile)
    try {
      val entry = jarFile.getJarEntry(ManifestPath)
      if (entry == null) {
        throw new IllegalArgumentException(s"Missing required manifest entry '$ManifestPath' in JAR: ${jarPath.getFileName}")
      }
      val inputStream = jarFile.getInputStream(entry)
      try {
        val reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
        val sb = new java.lang.StringBuilder()
        var line: String = reader.readLine()
        while (line != null) {
          sb.append(line).append('\n')
          line = reader.readLine()
        }
        parseJson(sb.toString)
      } finally {
        inputStream.close()
      }
    } finally {
      jarFile.close()
    }
  }

  /**
   * Parses raw manifest JSON text into a strongly-typed `PluginManifest`.
   */
  def parseJson(jsonText: String): PluginManifest = {
    val id = extractStringField(jsonText, "id")
      .getOrElse(throw new IllegalArgumentException("Plugin manifest missing required 'id' field"))
    val name = extractStringField(jsonText, "name")
      .getOrElse(id)
    val version = extractStringField(jsonText, "version")
      .getOrElse("1.0.0")
    val vendor = extractStringField(jsonText, "vendor")
      .getOrElse("Unknown")
    val description = extractStringField(jsonText, "description")
      .getOrElse("")
    val minUmbrixVersion = extractStringField(jsonText, "minUmbrixVersion")
      .getOrElse("1.0.0")
    val requiresRestart = extractBooleanField(jsonText, "requiresRestart")
      .getOrElse(false)
    val entrypoint = extractStringField(jsonText, "entrypoint")
      .getOrElse(throw new IllegalArgumentException("Plugin manifest missing required 'entrypoint' field"))

    val capabilities = extractStringArray(jsonText, "capabilities").flatMap { capStr =>
      try {
        Some(PluginCapability.valueOf(capStr.trim.toUpperCase))
      } catch {
        case _: IllegalArgumentException => None
      }
    }

    PluginManifest(
      id = id,
      name = name,
      version = version,
      vendor = vendor,
      description = description,
      minUmbrixVersion = minUmbrixVersion,
      requiresRestart = requiresRestart,
      capabilities = capabilities,
      entrypoint = entrypoint
    )
  }

  private def extractStringField(json: String, fieldName: String): Option[String] = {
    val pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]*)\"")
    val matcher = pattern.matcher(json)
    if (matcher.find()) {
      Some(matcher.group(1))
    } else {
      None
    }
  }

  private def extractBooleanField(json: String, fieldName: String): Option[Boolean] = {
    val pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*(true|false)")
    val matcher = pattern.matcher(json)
    if (matcher.find()) {
      Some(matcher.group(1).toBoolean)
    } else {
      None
    }
  }

  private def extractStringArray(json: String, fieldName: String): Array[String] = {
    val arrayPattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\\[([^\\]]*)\\]")
    val arrayMatcher = arrayPattern.matcher(json)
    if (arrayMatcher.find()) {
      val inside = arrayMatcher.group(1).trim
      if (inside.isEmpty) {
        Array.empty[String]
      } else {
        val itemPattern = Pattern.compile("\"([^\"]+)\"")
        val itemMatcher = itemPattern.matcher(inside)
        val buffer = scala.collection.mutable.ArrayBuffer[String]()
        while (itemMatcher.find()) {
          buffer += itemMatcher.group(1)
        }
        buffer.toArray
      }
    } else {
      Array.empty[String]
    }
  }
}
