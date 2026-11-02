package dev.phoneagent.app.adapters.screen

import android.view.accessibility.AccessibilityWindowInfo

/**
 * Windows found to show FLAG_SECURE content, by accessibility window id (ids are not reused while
 * a window lives). Only [probe] finds them, so a window is known secure from the first frame asked
 * of it; a later probe that captures it unmarks it. Bounded: the oldest marks are dropped first.
 */
internal class SecureWindows(
    private val keep: Int = KEPT,
) {
    private val marked = LinkedHashSet<Int>()

    /** Whether [windowId] was found secure the last time it was probed. */
    @Synchronized
    operator fun contains(windowId: Int): Boolean = windowId in marked

    /**
     * Captures the top application window of [windows] alone ([ScreenHost.windowScreenshot]) and
     * remembers what it shows. Returns whether that window is secure: as found now, or as last found
     * when the capture tells nothing (refused, timed out, before API 34).
     */
    suspend fun probe(
        host: ScreenHost,
        windows: List<AccessibilityWindowInfo>,
        waitMs: Long,
    ): Boolean {
        val top = ScreenReader.topApp(windows)?.first?.id ?: return false
        when (val shot = screenshotWithin(waitMs) { host.windowScreenshot(top) }) {
            Screenshot.Secure -> mark(top, secure = true)
            is Screenshot.Captured -> mark(top, secure = false).also { shot.release() }
            is Screenshot.Failed, null -> Unit
        }
        return top in this
    }

    @Synchronized
    private fun mark(
        windowId: Int,
        secure: Boolean,
    ) {
        marked -= windowId
        if (secure) marked += windowId
        while (marked.size > keep) marked -= marked.first()
    }

    private companion object {
        const val KEPT = 16
    }
}
