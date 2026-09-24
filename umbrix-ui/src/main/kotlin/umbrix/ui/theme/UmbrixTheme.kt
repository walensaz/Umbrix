package umbrix.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme

/**
 * Centralized semantic color tokens for Umbrix Dark Carbon IDE theme.
 * All UI components reference these constants rather than hardcoding Color literals.
 */
object UmbrixColors {
    // --- Surface & Backgrounds ---
    val surface = Color(0xFF1E1F22)                 // Main application window background
    val surfacePanel = Color(0xFF26282E)            // Toolbar, search bar backgrounds
    val surfaceElevated = Color(0xFF2B2D30)         // Active tab, progress track, elevated cards
    val surfaceGutter = Color(0xFF141416)           // Gutter, scrollbar track, status bar background
    val surfaceScrollbarTrack = Color(0xFF18181A)   // Vertical scrollbar track

    // --- Text Colors ---
    val textPrimary = Color(0xFFDFE1E5)             // Primary text (active tab, bright labels)
    val textSecondary = Color(0xFFBCBEC4)           // Secondary text (toolbar labels, font size)
    val textMuted = Color(0xFF8C909E)               // Muted text (inactive tabs, close icons, status)
    val textSubtle = Color(0xFF6C707E)              // Subtle text (scrollbar thumb hovered, tab close)
    val textDimmed = Color(0xFF5A5D6B)              // Dimmed text (line numbers, telemetry)
    val textFaint = Color(0xFF4E5157)               // Faint text (scrollbar thumb resting, empty state subtitle)
    val textEditor = Color(0xFFD4D7E1)              // Log text color in editor
    val textEmptyStateTitle = Color(0xFF6C707E)     // "No log file open" placeholder title
    val textEmptyStateSubtitle = Color(0xFF4E5157)  // Empty state instruction text

    // --- Gutter Line Numbers ---
    val gutterLineSelected = Color(0xFF90CAF9)      // Selected line (crisp light blue)
    val gutterLineActiveMatch = Color(0xFF81D4FA)   // Active search match (vibrant light blue)
    val gutterLineMatch = Color(0xFF4FC3F7)         // Search match (crisp cyan blue)
    val gutterLineFiltered = Color(0xFFE5C07B)      // Filtered mode (gold/yellow)
    val gutterLineNormal = Color(0xFF5A5D6B)        // Normal line number (muted grey)
    val gutterBorder = Color(0xFF2B2D30)            // 1dp vertical gutter separator

    // --- Row Selection & Match Backgrounds ---
    val rowSelected = Color(0x332675D9)             // Selected row (20% IntelliJ blue)
    val rowActiveMatch = Color(0x284FC3F7)          // Active match row (16% light blue)
    val rowFilteredMatch = Color(0x0C4FC3F7)        // Filtered match row (5% light blue)
    val rowMatch = Color(0x104FC3F7)                // Search match row (6% light blue)

    // --- Search Highlights ---
    val searchHighlightActive = Color(0xFFFF9800)   // Active match needle background (amber)
    val searchHighlightInactive = Color(0x66FFD54F) // Inactive match needle background (yellow 40%)
    val searchTextActive = Color(0xFF1E1F22)        // Active match text (dark carbon)
    val searchTextInactive = Color(0xFFFFFFFF)      // Inactive match text (white)

    // --- Scrollbar ---
    val scrollbarThumbDragging = Color(0xFF8C909E)  // Dragging thumb
    val scrollbarThumbHovered = Color(0xFF6C707E)   // Hovered thumb
    val scrollbarThumbResting = Color(0xFF4E5157)   // Resting thumb
    val scrollbarHorizontalUnhovered = Color(0x40808080) // Horizontal scrollbar unhovered
    val scrollbarHorizontalHovered = Color(0x90808080)   // Horizontal scrollbar hovered

    // --- Progress Bar ---
    val progressGradientStart = Color(0xFF2196F3)   // Indexing progress gradient start
    val progressGradientEnd = Color(0xFF00E5FF)     // Indexing progress gradient end
    val progressText = Color(0xFF4FC3F7)            // Indexing status & percentage text

    // --- Badges ---
    val badgeLinesText = Color(0xFF64B5F6)          // Total lines badge text
    val badgeLinesBackground = Color(0xFF1E3A5F)    // Total lines badge background
    val badgeSizeText = Color(0xFF81C784)           // File size badge text
    val badgeSizeBackground = Color(0xFF1E3F2E)     // File size badge background
    val badgeSimdText = Color(0xFFBA68C8)           // SIMD width badge text
    val badgeSimdBackground = Color(0xFF381E48)     // SIMD width badge background
    val badgeDurationText = Color(0xFFFFB74D)       // Index duration badge text
    val badgeDurationBackground = Color(0xFF422E1A) // Index duration badge background
    val badgeSuccessText = Color(0xFF81C784)        // Positive match count text
    val badgeWarningText = Color(0xFFFFB74D)        // Zero matches / warning text
    val badgeSuccessBackground = Color(0xFF1E3F2E)  // Positive badge background
    val badgeWarningBackground = Color(0xFF422E1A)  // Warning badge background
    val badgeErrorText = Color(0xFFEF5350)          // Error badge text
    val badgeErrorBackground = Color(0xFF381E1E)    // Error banner/badge background

    // --- Forensic Error Banner ---
    val errorIconColor = Color(0xFFEF5350)          // Warning icon
    val errorClassNameColor = Color(0xFFFF8A80)     // Exception class name
    val errorThreadColor = Color(0xFFB0BEC5)        // Thread identifier
    val errorMessageColor = Color(0xFFECEFF1)       // Error message text
    val errorFilePathColor = Color(0xFF90A4AE)      // Diagnostic file path

    // --- Temporal Navigation & Range Scrubber ---
    val scrubberTrack = Color(0xFF232529)           // Scrubber track background
    val scrubberFillStart = Color(0xFF2196F3)       // Selected range gradient start
    val scrubberFillEnd = Color(0xFF00E5FF)         // Selected range gradient end
    val scrubberHandle = Color(0xFF64B5F6)          // Handle knob fill
    val scrubberHandleBorder = Color(0xFF90CAF9)    // Handle knob border
    val scrubberTimestampLabel = Color(0xFFBCBEC4)  // Timestamp edge labels
    val scrubberTickGap = Color(0xFFFFB74D)         // Latency gap tick on scrubber
    val scrubberTickSevereGap = Color(0xFFFF5252)   // Severe latency gap tick on scrubber

    // --- Latency Gap Gutter Indicators ---
    val latencyGapAmber = Color(0xFFFFB74D)         // Moderate gap (>1s)
    val latencyGapRed = Color(0xFFFF5252)           // Severe gap (>5s)

    // --- Temporal Focus Mode ---
    val temporalFocusBadgeText = Color(0xFF4FC3F7)  // Focus mode badge text
    val temporalFocusBadgeBackground = Color(0xFF1A3A5C) // Focus mode badge background
    val temporalFocusDimOverlay = Color(0x99000000) // Minimap dim overlay outside focus
}

/**
 * Umbrix Dark Carbon IDE theme powered by JetBrains Jewel (IntelliJ New UI).
 */
@Composable
fun UmbrixTheme(content: @Composable () -> Unit) {
    IntUiTheme(isDark = true) {
        content()
    }
}
