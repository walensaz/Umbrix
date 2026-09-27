package umbrix.ui.bookmarks

/**
 * Standard bookmark tags for categorizing marked lines.
 */
enum class BookmarkTag(val displayName: String, val colorHex: Long) {
    IMPORTANT("Important", 0xFF61AFEF),   // Accent Blue
    ANOMALY("Anomaly", 0xFFE5C07B),       // Amber
    ERROR("Error", 0xFFE06C75),           // Red
    INVESTIGATION("Investigation", 0xFF98C379); // Green

    companion object {
        fun fromString(name: String): BookmarkTag {
            return entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true) }
                ?: IMPORTANT
        }
    }
}

/**
 * An annotated line bookmark pinned by an analyst within a document.
 *
 * @param lineIndex 0-based physical line index in the document
 * @param timestampMicros Microsecond epoch timestamp if parsed, or 0L
 * @param lineSnippet Clamped preview text of the line (<= 4096 chars)
 * @param note User forensic annotation / causal notes
 * @param tag Categorical severity / status tag
 * @param createdAtMs System wall-clock timestamp when bookmark was created
 */
data class DocumentBookmark(
    val lineIndex: Long,
    val timestampMicros: Long = 0L,
    val lineSnippet: String = "",
    val note: String = "",
    val tag: BookmarkTag = BookmarkTag.IMPORTANT,
    val createdAtMs: Long = System.currentTimeMillis()
)
