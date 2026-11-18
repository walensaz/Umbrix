package umbrix.mcp.dlp

import com.google.re2j.Pattern

/**
 * Client-Side Data Loss Prevention (DLP) & Secret Sanitization.
 *
 * Ensures that high-entropy credentials (AWS keys, JWTs, API tokens, passwords)
 * and PII are redacted before exiting the workstation to external LLMs or MCP clients.
 */
object DlpSanitizer {

  private val PasswordPattern: Pattern = Pattern.compile("(?i)(password|passwd|secret|token|api[_-]?key)\\s*[:=]\\s*[\"']?([^\"'\\s,;]+)[\"']?")
  private val BearerTokenPattern: Pattern = Pattern.compile("(?i)Bearer\\s+([A-Za-z0-9\\-_=]+\\.[A-Za-z0-9\\-_=]+\\.?[A-Za-z0-9\\-_=]*)")
  private val AwsKeyPattern: Pattern = Pattern.compile("(?i)(AKIA[0-9A-Z]{16})")
  private val CandidateTokenPattern: Pattern = Pattern.compile("(?i)[a-z0-9_\\-+=!@#$%^&*~/]{16,}")
  private val TimestampPattern: Pattern = Pattern.compile("^\\d{4}[-/]\\d{2}[-/]\\d{2}([T\\s]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?)?$")

  /**
   * Calculates Shannon entropy of a string token to detect high-entropy keys/secrets.
   */
  def shannonEntropy(s: String): Double = {
    if (s.isEmpty) {
      0.0
    } else {
      val frequencies = new java.util.HashMap[Char, Int]()
      var i = 0
      while (i < s.length) {
        val c = s.charAt(i)
        frequencies.put(c, frequencies.getOrDefault(c, 0) + 1)
        i += 1
      }
      var entropy = 0.0
      val len = s.length.toDouble
      val it = frequencies.values().iterator()
      while (it.hasNext) {
        val count = it.next()
        val p = count.toDouble / len
        entropy -= p * (math.log(p) / math.log(2.0))
      }
      entropy
    }
  }

  /**
   * Redacts known sensitive patterns and high-entropy secret payloads from text.
   */
  def sanitize(text: String, entropyThreshold: Double = 4.2): String = {
    if (text == null || text.isEmpty) {
      ""
    } else {
      var sanitized = PasswordPattern.matcher(text).replaceAll("$1=[REDACTED]")
      sanitized = BearerTokenPattern.matcher(sanitized).replaceAll("Bearer [REDACTED_JWT]")
      sanitized = AwsKeyPattern.matcher(sanitized).replaceAll("[REDACTED_AWS_KEY]")

      val matcher = CandidateTokenPattern.matcher(sanitized)
      var lastIndex = 0
      val sb = new java.lang.StringBuilder(sanitized.length)
      while (matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()
        val token = matcher.group()
        if (isHighEntropySecret(token, entropyThreshold)) {
          sb.append(sanitized, lastIndex, start)
          sb.append("[REDACTED_HIGH_ENTROPY]")
          lastIndex = end
        }
      }

      if (lastIndex == 0) {
        sanitized
      } else {
        if (lastIndex < sanitized.length) {
          sb.append(sanitized, lastIndex, sanitized.length)
        }
        sb.toString
      }
    }
  }

  private def isRedactedPlaceholder(token: String): Boolean = {
    (token.startsWith("[REDACTED") || token.contains("[REDACTED")) && token.contains("]")
  }

  private def isStandardTimestamp(token: String): Boolean = {
    TimestampPattern.matcher(token).matches() ||
    (token.length >= 19 && token.charAt(4) == '-' && token.charAt(7) == '-' && (token.charAt(10) == 'T' || token.charAt(10) == ' '))
  }

  private def isStandardPath(token: String): Boolean = {
    token.startsWith("/") ||
    token.startsWith("\\") ||
    token.startsWith("http://") ||
    token.startsWith("https://") ||
    token.startsWith("file://") ||
    token.contains("\\") ||
    (token.length >= 3 && token.charAt(0).isLetter && token.charAt(1) == ':' && (token.charAt(2) == '/' || token.charAt(2) == '\\')) ||
    (token.contains("/") && (
      token.endsWith(".log") || token.endsWith(".txt") || token.endsWith(".java") ||
      token.endsWith(".scala") || token.endsWith(".kt") || token.endsWith(".json") ||
      token.endsWith(".xml") || token.endsWith(".conf") || token.endsWith(".yaml") ||
      token.endsWith(".yml") || token.endsWith(".so") || token.endsWith(".dll")
    ))
  }

  private def hasMixedChars(token: String): Boolean = {
    var hasLetter = false
    var hasDigit = false
    var hasSymbol = false
    var i = 0
    while (i < token.length) {
      val c = token.charAt(i)
      if (c.isLetter) {
        hasLetter = true
      } else if (c.isDigit) {
        hasDigit = true
      } else {
        hasSymbol = true
      }
      i += 1
    }
    (hasLetter && (hasDigit || hasSymbol)) || (hasDigit && hasSymbol)
  }

  private def isHighEntropySecret(token: String, entropyThreshold: Double): Boolean = {
    if (token.length < 16) {
      false
    } else if (isRedactedPlaceholder(token) || isStandardTimestamp(token) || isStandardPath(token)) {
      false
    } else if (!hasMixedChars(token)) {
      false
    } else {
      shannonEntropy(token) >= entropyThreshold
    }
  }
}
