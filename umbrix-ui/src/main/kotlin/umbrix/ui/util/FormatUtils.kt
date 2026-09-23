package umbrix.ui.util

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/**
 * Option A: Native OS File Dialog for opening log files.
 */
fun showNativeOpenFileDialog(): String? {
    val fileDialog = FileDialog(null as Frame?, "Open Log File in Umbrix", FileDialog.LOAD)
    fileDialog.isVisible = true
    val directory = fileDialog.directory
    val file = fileDialog.file
    return if (directory != null && file != null) {
        File(directory, file).absolutePath
    } else {
        null
    }
}

/**
 * Native OS Save File Dialog for exporting trimmed / filtered sub-files.
 */
fun showNativeSaveFileDialog(defaultName: String): String? {
    val fileDialog = FileDialog(null as Frame?, "Save Sub-File Export", FileDialog.SAVE)
    fileDialog.file = defaultName
    fileDialog.isVisible = true
    val directory = fileDialog.directory
    val file = fileDialog.file
    return if (directory != null && file != null) {
        File(directory, file).absolutePath
    } else {
        null
    }
}

/**
 * Formats raw byte size into human-readable binary prefixes (B, KB, MB, GB, TB).
 */
fun formatByteSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val exp = (Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt()
    val pre = "KMGTPE"[exp - 1]
    return String.format(Locale.US, "%.2f %sB", bytes / Math.pow(1024.0, exp.toDouble()), pre)
}

/**
 * Formats a 64-bit integer with thousands commas for line numbers and counts.
 */
fun formatNumberWithCommas(number: Long): String {
    return NumberFormat.getNumberInstance(Locale.US).format(number)
}
