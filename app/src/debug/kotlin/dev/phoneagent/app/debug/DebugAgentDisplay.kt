package dev.phoneagent.app.debug

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import dev.phoneagent.privileged.IPrivilegedCore
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A virtual display rendering into this process's `ImageReader`, used by [HelperSelfCheck] to
 * prove on a device that frames really arrive. The real `SurfaceManager` and the frame pipeline
 * are WP14; this is debug-only scaffolding and deliberately minimal (latest frame, no rate limit).
 */
internal class DebugAgentDisplay(
    private val core: IPrivilegedCore,
    private val width: Int,
    private val height: Int,
    dpi: Int,
) : AutoCloseable {
    private val thread = HandlerThread("debug-agent-display").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    private val firstFrame = CountDownLatch(1)

    @Volatile
    private var latest: Bitmap? = null

    @Volatile
    private var lastFrameAt = 0L

    /** Display id, or a negative `DisplayResult` value when the helper refused. */
    val displayId: Int

    init {
        reader.setOnImageAvailableListener({ take() }, handler)
        displayId = core.createDisplay(reader.surface, width, height, dpi)
        if (displayId < 0) close()
    }

    // androidx.core's createBitmap would be nicer, but debug-only scaffolding is not worth a
    // dependency the release build would also carry.
    @Suppress("UseKtx")
    private fun take() {
        val image = reader.acquireLatestImage() ?: return
        try {
            val plane = image.planes[0]
            val strideWidth = plane.rowStride / plane.pixelStride
            val full = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer.rewind())
            val cropped = Bitmap.createBitmap(full, 0, 0, width, height)
            full.recycle()
            latest?.recycle()
            latest = cropped
            lastFrameAt = SystemClock.uptimeMillis()
            firstFrame.countDown()
        } finally {
            image.close()
        }
    }

    /**
     * Waits for a frame, then for [quietMs] without a new one (so the screenshot is not a frame of
     * the launch animation), and writes it as a PNG. Null on timeout or IO error.
     */
    fun writeFrame(
        target: File,
        timeoutMs: Long,
        quietMs: Long = QUIET_MS,
    ): File? {
        if (!firstFrame.await(timeoutMs, TimeUnit.MILLISECONDS)) Log.w(TAG, "no frame within $timeoutMs ms")
        awaitQuiet(quietMs, timeoutMs)
        val frame = latest
        return if (frame == null) null else write(frame, target)
    }

    /** Waits until no new frame arrived for [quietMs], or [timeoutMs] passed. */
    private fun awaitQuiet(
        quietMs: Long,
        timeoutMs: Long,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val since = SystemClock.uptimeMillis() - lastFrameAt
            if (lastFrameAt != 0L && since >= quietMs) return
            Thread.sleep(POLL_MS)
        }
        Log.w(TAG, "the display never went quiet for $quietMs ms")
    }

    private fun write(
        frame: Bitmap,
        target: File,
    ): File? =
        try {
            target.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            target
        } catch (e: IOException) {
            Log.w(TAG, "cannot write $target", e)
            null
        }

    override fun close() {
        handler.post {
            reader.close()
            latest?.recycle()
            latest = null
        }
        thread.quitSafely()
    }

    private companion object {
        const val TAG = "PhoneAgentSelfCheck"
        const val PNG_QUALITY = 100
        const val QUIET_MS = 500L
        const val POLL_MS = 50L
    }
}
