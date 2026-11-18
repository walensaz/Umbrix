package umbrix.ui.viewport

import org.roaringbitmap.longlong.Roaring64NavigableMap
import umbrix.core.session.LogSession
import umbrix.core.view.FilteredLineView
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViewportCoordinateMapperTest {

    @Test
    fun testValueClassSentinels() {
        assertFalse(VirtualRowIndex.Invalid.isValid)
        assertEquals(-1L, VirtualRowIndex.Invalid.value)
        assertTrue(VirtualRowIndex(0L).isValid)

        assertFalse(PhysicalLine.Invalid.isValid)
        assertEquals(-1L, PhysicalLine.Invalid.value)
        assertTrue(PhysicalLine(0L).isValid)
    }

    @Test
    fun testResolveVirtualRowBounds() {
        assertEquals(10L, ViewportCoordinateMapper.resolveVirtualRow(10L, 0, 25L).value)
        assertEquals(24L, ViewportCoordinateMapper.resolveVirtualRow(20L, 4, 25L).value)
        // Past end returns totalLinesCount (clamped sentinel for SelectionEdgeScroll)
        assertEquals(25L, ViewportCoordinateMapper.resolveVirtualRow(20L, 5, 25L).value)
        assertEquals(0L, ViewportCoordinateMapper.resolveVirtualRow(0L, 0, 0L).value)
    }

    @Test
    fun testResolvePhysicalLineRawAndTemporalModes() {
        val raw = ViewportCoordinateMapper.resolvePhysicalLine(
            virtualRow = VirtualRowIndex(7L),
            totalLinesCount = 20L,
            isFilteredMode = false,
            filteredView = null,
            isTemporalMode = false,
            temporalStartLine = 500L
        )
        assertEquals(7L, raw.value)

        val temporal = ViewportCoordinateMapper.resolvePhysicalLine(
            virtualRow = VirtualRowIndex(7L),
            totalLinesCount = 20L,
            isFilteredMode = false,
            filteredView = null,
            isTemporalMode = true,
            temporalStartLine = 500L
        )
        assertEquals(507L, temporal.value)
    }

    @Test
    fun testResolvePhysicalLineFilteredAndPrecedence() {
        val matches = Roaring64NavigableMap()
        matches.addLong(12L)
        matches.addLong(48L)
        matches.addLong(109L)
        val filteredView = FilteredLineView(matches, null)

        val resolved = ViewportCoordinateMapper.resolvePhysicalLine(
            virtualRow = VirtualRowIndex(1L),
            totalLinesCount = 3L,
            isFilteredMode = true,
            filteredView = filteredView,
            isTemporalMode = true,
            temporalStartLine = 999L
        )
        assertEquals(48L, resolved.value)
    }

    @Test
    fun testResolvePhysicalLineOutOfBoundsAndInvalidSentinels() {
        assertEquals(
            PhysicalLine.Invalid,
            ViewportCoordinateMapper.resolvePhysicalLine(
                virtualRow = VirtualRowIndex.Invalid,
                totalLinesCount = 10L,
                isFilteredMode = false,
                filteredView = null,
                isTemporalMode = false,
                temporalStartLine = 0L
            )
        )
        assertEquals(
            PhysicalLine.Invalid,
            ViewportCoordinateMapper.resolvePhysicalLine(
                virtualRow = VirtualRowIndex(10L),
                totalLinesCount = 10L,
                isFilteredMode = false,
                filteredView = null,
                isTemporalMode = false,
                temporalStartLine = 0L
            )
        )
        assertEquals(
            PhysicalLine.Invalid,
            ViewportCoordinateMapper.resolvePhysicalLine(
                virtualRow = VirtualRowIndex(0L),
                totalLinesCount = 0L,
                isFilteredMode = false,
                filteredView = null,
                isTemporalMode = false,
                temporalStartLine = 0L
            )
        )
    }

    @Test
    fun testVirtualLineCountAcrossAllViewModes() {
        val tempFile = Files.createTempFile("umbrix-mapper-test-", ".log")
        Files.writeString(tempFile, "line0\nline1\nline2\nline3\n")
        val session = LogSession.open(tempFile, false)
        try {
            val matches = Roaring64NavigableMap()
            matches.addLong(1L)
            matches.addLong(3L)
            val filteredView = FilteredLineView(matches, null)

            assertEquals(
                2L,
                ViewportCoordinateMapper.virtualLineCount(
                    session = session,
                    filteredView = filteredView,
                    isTemporalFocusActive = true,
                    temporalStartLine = 0L,
                    temporalEndLine = 3L
                )
            )
            assertEquals(
                2L,
                ViewportCoordinateMapper.virtualLineCount(
                    session = session,
                    filteredView = null,
                    isTemporalFocusActive = true,
                    temporalStartLine = 1L,
                    temporalEndLine = 2L
                )
            )
            assertEquals(
                0L,
                ViewportCoordinateMapper.virtualLineCount(
                    session = session,
                    filteredView = null,
                    isTemporalFocusActive = true,
                    temporalStartLine = 5L,
                    temporalEndLine = 2L
                )
            )
            assertEquals(
                4L,
                ViewportCoordinateMapper.virtualLineCount(
                    session = session,
                    filteredView = null,
                    isTemporalFocusActive = false,
                    temporalStartLine = 0L,
                    temporalEndLine = 0L
                )
            )
        } finally {
            session.close()
            Files.deleteIfExists(tempFile)
        }
    }
}
