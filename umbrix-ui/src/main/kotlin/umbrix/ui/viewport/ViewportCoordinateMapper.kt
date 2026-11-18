package umbrix.ui.viewport

import umbrix.core.session.LogSession
import umbrix.core.view.FilteredLineView

/**
 * Zero-cost 64-bit coordinate representing a 0-based row in the active view
 * (full document, temporal slice, or filtered view).
 *
 * Uses an unboxed `-1L` sentinel ([Invalid]) instead of nullable `VirtualRowIndex?`
 * so Kotlin 2.1 / Compose Desktop never allocates wrapper objects on hot viewport paths.
 */
@JvmInline
value class VirtualRowIndex(val value: Long) {
    inline val isValid: Boolean get() = value >= 0L

    companion object {
        val Invalid = VirtualRowIndex(-1L)
    }
}

/**
 * Zero-cost 64-bit coordinate representing a 0-based physical line in the underlying log file.
 *
 * Uses an unboxed `-1L` sentinel ([Invalid]) instead of nullable `PhysicalLine?`
 * so hot Compose render loops, layout callbacks, and pointer gestures remain allocation-free.
 */
@JvmInline
value class PhysicalLine(val value: Long) {
    inline val isValid: Boolean get() = value >= 0L

    companion object {
        val Invalid = PhysicalLine(-1L)
    }
}

/**
 * Non-generic SAM callback for row text layout updates.
 *
 * Keeping `rowIndex` as primitive `Int` on a dedicated `fun interface` avoids generic
 * `Function2<ScreenRowIndex, ...>` boxing on every row's `SideEffect`.
 */
fun interface OnRowTextLayout {
    operator fun invoke(rowIndex: Int, entry: RowTextLayout?)
}

/**
 * Single source of truth for translating between rendered screen rows, virtual view rows,
 * and 64-bit physical log line indices across raw, temporal-focus, and filtered views.
 */
internal object ViewportCoordinateMapper {

    fun resolveVirtualRow(topVisibleLine: Long, rowOffset: Int, totalLinesCount: Long): VirtualRowIndex {
        return VirtualRowIndex(SelectionEdgeScroll.virtualRowAt(topVisibleLine, rowOffset, totalLinesCount))
    }

    fun resolvePhysicalLine(
        virtualRow: VirtualRowIndex,
        totalLinesCount: Long,
        isFilteredMode: Boolean,
        filteredView: FilteredLineView?,
        isTemporalMode: Boolean,
        temporalStartLine: Long
    ): PhysicalLine {
        val rawVirtual = virtualRow.value
        if (rawVirtual < 0L || rawVirtual >= totalLinesCount) {
            return PhysicalLine.Invalid
        }
        val resolved = when {
            isFilteredMode && filteredView != null -> filteredView.physicalLineForVirtualIndex(rawVirtual)
            isTemporalMode -> temporalStartLine + rawVirtual
            else -> rawVirtual
        }
        return PhysicalLine(resolved)
    }

    fun virtualLineCount(
        session: LogSession,
        filteredView: FilteredLineView?,
        isTemporalFocusActive: Boolean,
        temporalStartLine: Long,
        temporalEndLine: Long
    ): Long {
        return when {
            filteredView != null -> filteredView.totalFilteredLines()
            isTemporalFocusActive -> (temporalEndLine - temporalStartLine + 1L).coerceAtLeast(0L)
            else -> session.totalLines()
        }
    }
}
