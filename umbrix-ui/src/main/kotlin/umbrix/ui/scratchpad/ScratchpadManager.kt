package umbrix.ui.scratchpad

import androidx.compose.runtime.mutableStateListOf

/**
 * Manages virtual scratchpad buffers within the Umbrix workstation.
 */
class ScratchpadManager {
    val sessions = mutableStateListOf<ScratchpadSession>()
    private var sequenceNumber = 1

    fun createSession(initialContent: String = "", customTitle: String? = null): ScratchpadSession {
        val title = customTitle ?: "scratch-${sequenceNumber++}.log"
        val session = ScratchpadSession(
            initialTitle = title,
            initialContent = initialContent
        )
        sessions.add(session)
        return session
    }

    fun closeSession(id: String) {
        sessions.removeAll { it.id == id }
    }

    fun getSession(id: String): ScratchpadSession? {
        return sessions.firstOrNull { it.id == id }
    }

    /**
     * Appends a log snippet to the active or latest scratchpad session.
     * If no session exists, creates a new one automatically.
     */
    fun appendToActiveOrNew(
        sourceTitle: String,
        lineIndex: Long,
        timestampMicros: Long,
        snippet: String
    ): ScratchpadSession {
        val target = sessions.lastOrNull() ?: createSession()
        target.appendSnippet(sourceTitle, lineIndex, timestampMicros, snippet)
        return target
    }

    fun clear() {
        sessions.clear()
        sequenceNumber = 1
    }
}
