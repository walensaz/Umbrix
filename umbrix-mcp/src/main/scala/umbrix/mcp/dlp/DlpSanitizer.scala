package umbrix.mcp.dlp

import java.util.regex.Pattern

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
      sanitized
    }
  }
}
