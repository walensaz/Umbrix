package umbrix.core.plugin

import java.net.{URL, URLClassLoader}
import java.nio.file.Path

/**
 * Child-first isolated classloader for Umbrix plugins.
 *
 * Ensures fat/uber plugin JARs bundle their own third-party dependencies (e.g. Jackson,
 * SQLite, Arrow, Protobuf) without version conflicts against the host application or other plugins,
 * while transparently delegating all shared runtime platforms (JDK, Scala 3, Kotlin, Compose Desktop,
 * Skia, RoaringBitmap, Fastutil, and the Umbrix Plugin SDK) to the host classloader.
 *
 * Enforces architectural boundaries by prohibiting direct access to internal engine packages
 * (`umbrix.core.*`, `umbrix.simd.*`, `umbrix.ui.*`).
 */
class PluginClassLoader(
  val jarPath: Path,
  val parentClassLoader: ClassLoader
) extends URLClassLoader(Array[URL](jarPath.toUri.toURL), parentClassLoader) {

  private val SharedHostPackagePrefixes: Array[String] = Array(
    "java.",
    "jdk.",
    "javax.",
    "sun.",
    "com.sun.",
    "scala.",
    "kotlin.",
    "kotlinx.coroutines.",
    "androidx.compose.",
    "org.jetbrains.skia.",
    "org.jetbrains.skiko.",
    "org.jetbrains.jewel.",
    "org.roaringbitmap.",
    "it.unimi.dsi.fastutil.",
    "org.slf4j.",
    "umbrix.plugin.api.",
    "umbrix.plugin.ui."
  )

  private val SharedHostExactClasses: Set[String] = Set(
    "umbrix.index.RecordBoundaryFramer",
    "umbrix.index.RecordTextFormatter",
    "umbrix.vfs.LogStorageReader"
  )

  private val BlockedInternalPrefixes: Array[String] = Array(
    "umbrix.core.",
    "umbrix.simd.",
    "umbrix.ui.",
    "umbrix.mcp.",
    "umbrix.app."
  )

  override protected def loadClass(name: String, resolve: Boolean): Class[?] = {
    getClassLoadingLock(name).synchronized {
      // 1. Check if class is already loaded
      val loaded = findLoadedClass(name)
      if (loaded != null) {
        if (resolve) resolveClass(loaded)
        loaded
      } else if (isSharedHostClass(name)) {
        // 2. Delegate shared platform and public SDK contracts to host classloader
        parentClassLoader.loadClass(name)
      } else if (isBlockedInternalClass(name)) {
        // 3. Reject unauthorized access to internal workstation engine packages
        throw new ClassNotFoundException(
          s"Access to internal Umbrix engine class '$name' is prohibited for plugins. Use the public SPI contracts in 'umbrix.plugin.api.*'."
        )
      } else {
        // 4. Child-first: attempt to load from the plugin JAR first
        try {
          val childClass = findClass(name)
          if (resolve) {
            resolveClass(childClass)
          }
          childClass
        } catch {
          case _: ClassNotFoundException =>
            // Fall back to parent classloader if not present in child JAR
            val parentClass = parentClassLoader.loadClass(name)
            if (resolve) {
              resolveClass(parentClass)
            }
            parentClass
        }
      }
    }
  }

  private def isSharedHostClass(name: String): Boolean = {
    SharedHostExactClasses.contains(name) || SharedHostPackagePrefixes.exists(prefix => name.startsWith(prefix))
  }

  private def isBlockedInternalClass(name: String): Boolean = {
    BlockedInternalPrefixes.exists(prefix => name.startsWith(prefix))
  }
}
