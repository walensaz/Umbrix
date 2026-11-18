package umbrix.ui.toast

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToastManagerTest {

    @BeforeTest
    fun setUp() {
        ToastManager.clearAll()
    }

    @Test
    fun testShowToastAppendsToActiveList() {
        ToastManager.info("Operation in progress")
        assertEquals(1, ToastManager.activeToasts.size)
        val toast = ToastManager.activeToasts.first()
        assertEquals("Operation in progress", toast.message)
        assertEquals(ToastType.INFO, toast.type)
    }

    @Test
    fun testToastTypes() {
        ToastManager.success("File saved")
        ToastManager.warn("Disk space low")
        ToastManager.error("Failed to read")

        assertEquals(3, ToastManager.activeToasts.size)
        assertEquals(ToastType.SUCCESS, ToastManager.activeToasts[0].type)
        assertEquals(ToastType.WARNING, ToastManager.activeToasts[1].type)
        assertEquals(ToastType.ERROR, ToastManager.activeToasts[2].type)
    }

    @Test
    fun testDismissRemovesTargetToast() {
        ToastManager.info("First")
        ToastManager.info("Second")
        assertEquals(2, ToastManager.activeToasts.size)

        val firstId = ToastManager.activeToasts.first().id
        ToastManager.dismiss(firstId)

        assertEquals(1, ToastManager.activeToasts.size)
        assertEquals("Second", ToastManager.activeToasts.first().message)
    }

    @Test
    fun testMaxConcurrentToastsEvictsOldest() {
        for (i in 1..(ToastManager.MAX_CONCURRENT_TOASTS + 2)) {
            ToastManager.info("Toast $i")
        }

        assertEquals(ToastManager.MAX_CONCURRENT_TOASTS, ToastManager.activeToasts.size)
        assertEquals("Toast 3", ToastManager.activeToasts.first().message)
        assertEquals("Toast 6", ToastManager.activeToasts.last().message)
    }

    @Test
    fun testBlankMessageIsIgnored() {
        ToastManager.info("   ")
        assertTrue(ToastManager.activeToasts.isEmpty())
    }
}
