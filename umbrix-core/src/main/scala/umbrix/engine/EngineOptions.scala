package umbrix.engine

import umbrix.core.profile.LogLanguageProfile

/**
 * Configuration options for opening a standalone `LogEngine` session.
 *
 * @param isLiveStream if true, opens via positional dual-path I/O for active tailing;
 *                     if false, maps into 64-bit continuous address space for maximum throughput
 * @param profile optional log language profile (e.g. JavaJvmProfile) for syntax and severity
 * @param maxLineLength maximum length in characters decoded for viewport line text
 */
final case class EngineOptions(
  isLiveStream: Boolean = false,
  profile: Option[LogLanguageProfile] = None,
  maxLineLength: Int = 4096
)

object EngineOptions {
  val Default: EngineOptions = EngineOptions()
}
