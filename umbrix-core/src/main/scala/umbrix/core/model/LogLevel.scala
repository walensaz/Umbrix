package umbrix.core.model

/**
 * Standard log severity levels for discrete bitset filtering.
 */
enum LogLevel {
  case Fatal, Error, Warn, Info, Debug, Trace
}

object LogLevel {

  /**
   * Resolves a LogLevel enum from a raw string, ignoring case.
   */
  def fromString(name: String): Option[LogLevel] = {
    name.trim.toUpperCase match {
      case "FATAL" | "CRITICAL" => Some(LogLevel.Fatal)
      case "ERROR" | "ERR" => Some(LogLevel.Error)
      case "WARN" | "WARNING" => Some(LogLevel.Warn)
      case "INFO" => Some(LogLevel.Info)
      case "DEBUG" => Some(LogLevel.Debug)
      case "TRACE" => Some(LogLevel.Trace)
      case _ => None
    }
  }
}
