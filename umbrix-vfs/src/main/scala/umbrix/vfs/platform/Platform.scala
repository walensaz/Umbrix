package umbrix.vfs.platform

import java.util.Locale

enum OsType {
  case Windows
  case Linux
  case MacOS
  case Other
}

enum Architecture {
  case X86_64
  case AArch64
  case Other
}

/**
 * Operating system and hardware platform runtime detection.
 */
object Platform {

  val os: OsType = {
    val osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
    if (osName.contains("win")) {
      OsType.Windows
    } else if (osName.contains("linux")) {
      OsType.Linux
    } else if (osName.contains("mac") || osName.contains("darwin")) {
      OsType.MacOS
    } else {
      OsType.Other
    }
  }

  val arch: Architecture = {
    val osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)
    if (osArch.contains("amd64") || osArch.contains("x86_64")) {
      Architecture.X86_64
    } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
      Architecture.AArch64
    } else {
      Architecture.Other
    }
  }

  inline def isWindows: Boolean = os == OsType.Windows
  inline def isLinux: Boolean = os == OsType.Linux
  inline def isMacOS: Boolean = os == OsType.MacOS
  inline def isPosix: Boolean = isLinux || isMacOS
  inline def is64Bit: Boolean = {
    val sunArchDataModel = System.getProperty("sun.arch.data.model", "")
    sunArchDataModel == "64" || arch == Architecture.X86_64 || arch == Architecture.AArch64
  }
}
