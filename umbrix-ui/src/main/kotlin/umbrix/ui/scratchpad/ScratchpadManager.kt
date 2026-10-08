package umbrix.ui.scratchpad

import androidx.compose.runtime.mutableStateListOf
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/**
 * Manages virtual scratchpad buffers within the Umbrix workstation with optional disk persistence.
 */
class ScratchpadManager(
    private val storageDirectory: File? = getDefaultStorageDirectory()
) {
    private val logger = LoggerFactory.getLogger(ScratchpadManager::class.java)
    val sessions = mutableStateListOf<ScratchpadSession>()
    private var sequenceNumber = 1

    init {
        loadPersistedSessions()
    }

    fun createSession(initialContent: String = "", customTitle: String? = null): ScratchpadSession {
        val title = customTitle ?: "scratch-${sequenceNumber++}.log"
        val session = ScratchpadSession(
            initialTitle = title,
            initialContent = initialContent
        )
        sessions.add(session)
        persistSession(session)
        return session
    }

    fun renameSession(id: String, newTitle: String): Boolean {
        val session = getSession(id) ?: return false
        val trimmed = newTitle.trim()
        if (trimmed.isNotBlank()) {
            session.title = trimmed
            session.isDirty = true
            persistSession(session)
            return true
        }
        return false
    }

    fun closeSession(id: String) {
        sessions.removeAll { it.id == id }
    }

    fun deleteSession(id: String): Boolean {
        val removed = sessions.removeAll { it.id == id }
        if (removed && storageDirectory != null) {
            try {
                val file = File(storageDirectory, "$id.json")
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Throwable) {
                logger.warn("Failed to delete scratchpad file for id {}: {}", id, e.message)
            }
        }
        return removed
    }

    fun deleteSession(session: ScratchpadSession): Boolean {
        return deleteSession(session.id)
    }

    fun getSession(id: String): ScratchpadSession? {
        return sessions.firstOrNull { it.id == id }
    }

    fun findSessions(query: String): List<ScratchpadSession> {
        if (query.isBlank()) return sessions.toList()
        val lower = query.trim().lowercase()
        return sessions.filter { s ->
            s.title.lowercase().contains(lower) ||
            s.content.lowercase().contains(lower) ||
            (s.diskPath != null && s.diskPath!!.lowercase().contains(lower))
        }
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
        persistSession(target)
        return target
    }

    fun persistSession(session: ScratchpadSession) {
        val dir = storageDirectory ?: return
        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val targetFile = File(dir, "${session.id}.json")
            val json = buildString {
                appendLine("{")
                appendLine("  \"id\": \"${session.id}\",")
                appendLine("  \"title\": \"${escapeJson(session.title)}\",")
                appendLine("  \"diskPath\": \"${session.diskPath?.let { escapeJson(it) } ?: ""}\",")
                appendLine("  \"createdAt\": ${session.createdAt},")
                appendLine("  \"content\": \"${escapeJson(session.content)}\"")
                appendLine("}")
            }
            targetFile.writeText(json, Charsets.UTF_8)
        } catch (e: Throwable) {
            logger.warn("Failed to persist scratchpad {}: {}", session.title, e.message)
        }
    }

    private fun loadPersistedSessions() {
        val dir = storageDirectory ?: return
        if (!dir.exists() || !dir.isDirectory) return
        try {
            val files = dir.listFiles { _, name -> name.endsWith(".json") } ?: return
            for (file in files) {
                try {
                    val text = file.readText(Charsets.UTF_8)
                    val session = parseScratchpadJson(text)
                    if (session != null && sessions.none { it.id == session.id }) {
                        sessions.add(session)
                    }
                } catch (e: Throwable) {
                    logger.debug("Failed to parse scratchpad file {}: {}", file.name, e.message)
                }
            }
        } catch (e: Throwable) {
            logger.warn("Failed to scan scratchpads directory: {}", e.message)
        }
    }

    fun clear() {
        if (storageDirectory != null && storageDirectory.exists()) {
            for (session in sessions) {
                val f = File(storageDirectory, "${session.id}.json")
                f.delete()
            }
        }
        sessions.clear()
        sequenceNumber = 1
    }

    companion object {
        fun getDefaultStorageDirectory(): File? {
            return try {
                val userHome = System.getProperty("user.home") ?: "."
                val dir = File(File(userHome, ".umbrix"), "scratchpads")
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                dir
            } catch (_: Throwable) {
                null
            }
        }

        private fun escapeJson(raw: String): String = buildString(raw.length + 16) {
            for (c in raw) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }

        private fun unescapeJson(escaped: String): String = buildString(escaped.length) {
            var i = 0
            while (i < escaped.length) {
                val c = escaped[i]
                if (c == '\\' && i + 1 < escaped.length) {
                    when (val next = escaped[i + 1]) {
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        else -> append(next)
                    }
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }

        private fun parseScratchpadJson(json: String): ScratchpadSession? {
            var id = UUID.randomUUID().toString()
            var title = "scratch-1.log"
            var content = ""
            var diskPath: String? = null

            for (line in json.lines()) {
                val trimmed = line.trim().trimEnd(',')
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx > 0) {
                    val key = trimmed.substring(0, colonIdx).trim().trim('"')
                    val rawVal = trimmed.substring(colonIdx + 1).trim().removeSurrounding("\"")
                    when (key) {
                        "id" -> id = rawVal
                        "title" -> title = unescapeJson(rawVal)
                        "diskPath" -> diskPath = if (rawVal.isBlank()) null else unescapeJson(rawVal)
                        "content" -> content = unescapeJson(rawVal)
                    }
                }
            }

            val session = ScratchpadSession(id = id, initialTitle = title, initialContent = content)
            session.diskPath = diskPath
            return session
        }
    }
}
