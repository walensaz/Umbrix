package umbrix.ui.state

import androidx.compose.ui.input.key.KeyEvent

/**
 * Global keyboard shortcut dispatcher routing key events intercepted at the top-level Window
 * into the active workstation screen and tools.
 */
object WorkstationKeyDispatcher {
    var onGlobalKeyEvent: ((KeyEvent) -> Boolean)? = null

    fun handleKeyEvent(event: KeyEvent): Boolean {
        return onGlobalKeyEvent?.invoke(event) ?: false
    }
}
