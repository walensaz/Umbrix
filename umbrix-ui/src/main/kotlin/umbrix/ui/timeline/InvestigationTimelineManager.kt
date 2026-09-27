package umbrix.ui.timeline

import androidx.compose.runtime.mutableStateListOf

/**
 * Central state coordinator for the Multi-Document Incident Timeline.
 * Tracks pinned events across all open files, supports microsecond chronological sorting,
 * multi-row selection, and batch movements.
 */
class InvestigationTimelineManager {
    val events = mutableStateListOf<TimelineEvent>()
    val selectedEventIds = mutableStateListOf<String>()

    /**
     * Pinned event addition. Clamps content to 4096 characters to preserve zero-heap layout invariants.
     */
    fun addEvent(
        sourceDocumentPath: String,
        sourceDocumentTitle: String,
        lineIndex: Long,
        timestampMicros: Long,
        lineContent: String,
        tag: EventTag = EventTag.ANOMALY,
        userNote: String = ""
    ): TimelineEvent {
        val clampedContent = if (lineContent.length > 4096) lineContent.substring(0, 4096) else lineContent
        val event = TimelineEvent(
            sourceDocumentPath = sourceDocumentPath,
            sourceDocumentTitle = sourceDocumentTitle,
            lineIndex = lineIndex,
            timestampMicros = timestampMicros,
            lineContent = clampedContent,
            tag = tag,
            userNote = userNote
        )
        events.add(event)
        return event
    }

    fun removeEvent(id: String) {
        events.removeAll { it.id == id }
        selectedEventIds.remove(id)
    }

    fun removeSelected() {
        if (selectedEventIds.isEmpty()) return
        events.removeAll { selectedEventIds.contains(it.id) }
        selectedEventIds.clear()
    }

    fun clear() {
        events.clear()
        selectedEventIds.clear()
    }

    fun updateNote(id: String, note: String) {
        val idx = events.indexOfFirst { it.id == id }
        if (idx >= 0) {
            events[idx] = events[idx].copy(userNote = note)
        }
    }

    fun updateTag(id: String, tag: EventTag) {
        val idx = events.indexOfFirst { it.id == id }
        if (idx >= 0) {
            events[idx] = events[idx].copy(tag = tag)
        }
    }

    fun setBatchTag(tag: EventTag) {
        for (i in events.indices) {
            val event = events[i]
            if (selectedEventIds.contains(event.id)) {
                events[i] = event.copy(tag = tag)
            }
        }
    }

    /**
     * Sorts all events strictly by timestampMicros ascending.
     * Events without timestamps (0L) are placed at the end.
     */
    fun sortChronologically() {
        val sorted = events.sortedWith(Comparator { a, b ->
            when {
                a.timestampMicros == b.timestampMicros -> a.lineIndex.compareTo(b.lineIndex)
                a.timestampMicros == 0L -> 1
                b.timestampMicros == 0L -> -1
                else -> a.timestampMicros.compareTo(b.timestampMicros)
            }
        })
        events.clear()
        events.addAll(sorted)
    }

    // --- Multi-Row Selection ---

    fun isSelected(id: String): Boolean = selectedEventIds.contains(id)

    fun toggleSelection(id: String) {
        if (selectedEventIds.contains(id)) {
            selectedEventIds.remove(id)
        } else {
            selectedEventIds.add(id)
        }
    }

    fun selectRange(fromId: String, toId: String) {
        val fromIdx = events.indexOfFirst { it.id == fromId }
        val toIdx = events.indexOfFirst { it.id == toId }
        if (fromIdx >= 0 && toIdx >= 0) {
            val start = minOf(fromIdx, toIdx)
            val end = maxOf(fromIdx, toIdx)
            for (i in start..end) {
                val id = events[i].id
                if (!selectedEventIds.contains(id)) {
                    selectedEventIds.add(id)
                }
            }
        }
    }

    fun selectAll() {
        selectedEventIds.clear()
        events.forEach { selectedEventIds.add(it.id) }
    }

    fun deselectAll() {
        selectedEventIds.clear()
    }

    // --- Movement: Single & Multi-Row Batch ---

    fun moveSingleUp(id: String) {
        val idx = events.indexOfFirst { it.id == id }
        if (idx > 0) {
            val item = events.removeAt(idx)
            events.add(idx - 1, item)
        }
    }

    fun moveSingleDown(id: String) {
        val idx = events.indexOfFirst { it.id == id }
        if (idx >= 0 && idx < events.size - 1) {
            val item = events.removeAt(idx)
            events.add(idx + 1, item)
        }
    }

    /**
     * Shifts all selected items up by 1 position as a group, preserving relative internal order.
     */
    fun moveSelectedUp() {
        if (selectedEventIds.isEmpty()) return
        val selSet = selectedEventIds.toSet()
        for (i in 1 until events.size) {
            if (selSet.contains(events[i].id) && !selSet.contains(events[i - 1].id)) {
                val temp = events[i]
                events[i] = events[i - 1]
                events[i - 1] = temp
            }
        }
    }

    /**
     * Shifts all selected items down by 1 position as a group, preserving relative internal order.
     */
    fun moveSelectedDown() {
        if (selectedEventIds.isEmpty()) return
        val selSet = selectedEventIds.toSet()
        for (i in (events.size - 2) downTo 0) {
            if (selSet.contains(events[i].id) && !selSet.contains(events[i + 1].id)) {
                val temp = events[i]
                events[i] = events[i + 1]
                events[i + 1] = temp
            }
        }
    }

    /**
     * Moves all selected items to the top of the timeline.
     */
    fun moveSelectedToTop() {
        if (selectedEventIds.isEmpty()) return
        val selSet = selectedEventIds.toSet()
        val selected = events.filter { selSet.contains(it.id) }
        val unselected = events.filter { !selSet.contains(it.id) }
        events.clear()
        events.addAll(selected)
        events.addAll(unselected)
    }

    /**
     * Moves all selected items to the bottom of the timeline.
     */
    fun moveSelectedToBottom() {
        if (selectedEventIds.isEmpty()) return
        val selSet = selectedEventIds.toSet()
        val selected = events.filter { selSet.contains(it.id) }
        val unselected = events.filter { !selSet.contains(it.id) }
        events.clear()
        events.addAll(unselected)
        events.addAll(selected)
    }
}
