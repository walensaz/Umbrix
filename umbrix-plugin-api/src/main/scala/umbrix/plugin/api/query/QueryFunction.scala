package umbrix.plugin.api.query

import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.plugin.api.PluginSessionView

/**
 * Custom query function pluggable into the Umbrix query and filter engine.
 *
 * Evaluates arbitrary domain logic (e.g. `gc_pause(>50ms)`, `status_code(5xx)`)
 * and returns matching 64-bit record indices encoded in a [[Roaring64NavigableMap]].
 */
trait QueryFunction {
  def name: String
  def description: String

  def evaluate(
    session: PluginSessionView,
    arguments: Array[String]
  ): Roaring64NavigableMap
}
