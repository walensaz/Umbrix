package umbrix.ui.toast

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Central singleton orchestrating non-blocking, transient workstation toast notifications.
 *
 * Designed with a strict upper bound of [MAX_CONCURRENT_TOASTS] to preserve the <150MB RSS invariant.
 */
object ToastManager {

    const val MAX_CONCURRENT_TOASTS: Int = 4

    private val idCounter = AtomicLong(1L)
    private val scope = CoroutineScope(Dispatchers.Main)

    /** Live reactive list of active toasts displayed by [ToastHost]. */
    val activeToasts = mutableStateListOf<ToastMessage>()

    fun show(
        message: String,
        type: ToastType = ToastType.INFO,
        durationMs: Long = 2500L
    ) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return

        val id = idCounter.getAndIncrement()
        val toast = ToastMessage(id, trimmed, type, durationMs)

        // Enforce bounded memory footprint: evict oldest if queue exceeds capacity
        if (activeToasts.size >= MAX_CONCURRENT_TOASTS) {
            activeToasts.removeAt(0)
        }
        activeToasts.add(toast)

        scope.launch {
            delay(durationMs)
            dismiss(id)
        }
    }

    fun info(message: String, durationMs: Long = 2500L) {
        show(message, ToastType.INFO, durationMs)
    }

    fun success(message: String, durationMs: Long = 2500L) {
        show(message, ToastType.SUCCESS, durationMs)
    }

    fun warn(message: String, durationMs: Long = 3000L) {
        show(message, ToastType.WARNING, durationMs)
    }

    fun error(message: String, durationMs: Long = 4000L) {
        show(message, ToastType.ERROR, durationMs)
    }

    fun dismiss(id: Long) {
        activeToasts.removeAll { it.id == id }
    }

    fun clearAll() {
        activeToasts.clear()
    }
}
