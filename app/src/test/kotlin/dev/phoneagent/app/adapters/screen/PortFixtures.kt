package dev.phoneagent.app.adapters.screen

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import dev.phoneagent.core.ports.ElementRef
import dev.phoneagent.core.ports.OcrLine
import dev.phoneagent.core.ports.ScreenDiff
import dev.phoneagent.core.ports.ScreenFlag
import dev.phoneagent.core.ports.ScreenSnapshot
import dev.phoneagent.core.ports.SnapshotAnalyzer
import dev.phoneagent.core.ports.SurfaceKind
import dev.phoneagent.core.ports.SurfaceRef
import dev.phoneagent.core.ports.TreeQuality
import dev.phoneagent.core.ports.UiElement
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/** Device services without a device; the clipboard cannot be read (as in the background). */
internal class TestSystem : ScreenSystem {
    override val ownPackage: String = OWN
    override var clipboard: ClipboardAccess =
        object : ClipboardAccess {
            override fun save(): SavedClip? = null

            override fun put(
                text: String,
                sensitive: Boolean,
            ) = error("not saved, so never put")

            override fun restore(saved: SavedClip) = error("not saved, so never restored")
        }

    /** What [uptimeMs] answers; tests move it forward. */
    var now: Long = 0

    override fun uptimeMs(): Long = now

    override fun locked(): Boolean = false

    override fun pickerPackages(): Set<String> = emptySet()

    override fun isActivity(
        packageName: String,
        className: String,
    ): Boolean = false

    override fun launchIntent(packageName: String): Intent? = Intent(Intent.ACTION_MAIN).setPackage(packageName)

    override fun resolvedPackage(intent: Intent): String? = intent.`package`

    override fun start(
        intent: Intent,
        displayId: Int,
    ): StartResult = StartResult.Started
}

/**
 * Fingerprint over what is visible (no refs, parents or view ids, like core's), and the diff WP06
 * makes: the two screens are paired only by ref, so a ref that changes is one removal and one addition.
 */
internal class RefDiffAnalyzer : SnapshotAnalyzer {
    override fun fingerprint(
        pkg: String?,
        activity: String?,
        elements: List<UiElement>,
        ocr: List<OcrLine>?,
    ): String = "$pkg|" + elements.joinToString(";") { "${it.window}:${it.role}:${it.name}=${it.value}@${it.bounds}" }

    override fun quality(
        elements: List<UiElement>,
        surface: SurfaceRef,
        flags: Set<ScreenFlag>,
    ): TreeQuality = if (elements.isEmpty()) TreeQuality.EMPTY else TreeQuality.GOOD

    override fun diff(
        before: ScreenSnapshot,
        after: ScreenSnapshot,
    ): ScreenDiff {
        val was = before.elements.associateBy { it.ref }
        val now = after.elements.associateBy { it.ref }
        return ScreenDiff(
            added = after.elements.filter { it.ref !in was },
            removed = before.elements.map { it.ref }.filter { it !in now },
            changed = after.elements.filter { element -> was[element.ref]?.let { it != element } == true },
        )
    }
}

/** A port on the main display of [host], with I/O on the test scheduler. */
internal fun TestScope.mainPort(
    host: FakeHost,
    system: ScreenSystem = TestSystem(),
    analyzer: SnapshotAnalyzer = RefDiffAnalyzer(),
    timings: ScreenTimings = ScreenTimings(),
): AndroidScreen =
    AndroidScreen(
        surface = SurfaceRef(0, SurfaceKind.MAIN),
        connection = { host },
        system = system,
        analyzer = analyzer,
        options = AndroidScreen.Options(timings = timings, io = StandardTestDispatcher(testScheduler)),
    )

/** Shows [nodes] under a full-display root in window 1 of [pkg], above [extra] windows. */
internal fun FakeHost.show(
    vararg nodes: AccessibilityNodeInfo,
    pkg: String = APP,
    extra: List<AccessibilityWindowInfo> = emptyList(),
) {
    val root = node("android.widget.FrameLayout", GEOMETRY.pixels, pkg).with(*nodes)
    windows = mapOf(0 to listOf(window(root, id = 1, layer = 1)) + extra)
}

/** A clickable button labelled [name]. */
internal fun button(
    name: String,
    bounds: Rect,
    pkg: String = APP,
    viewId: String? = null,
): AccessibilityNodeInfo =
    node("android.widget.Button", bounds, pkg) {
        text = name
        isClickable = true
        viewId?.let { viewIdResourceName = "$pkg:id/$it" }
    }

/** Height of a [Feed] row in pixels. */
internal const val ROW_PX = 100

/**
 * A scrollable list of people, one row each: the name and a "Like" button that every row has
 * (same name, same view id). Rows are plain containers (not clickable, so BRIEF drops them) unless
 * [clickableRows]. Every node is recorded in [likes] / [rows] by person, for asserting what was hit.
 */
internal class Feed(
    private val clickableRows: Boolean = false,
) {
    val likes = HashMap<String, AccessibilityNodeInfo>()
    val rows = HashMap<String, AccessibilityNodeInfo>()

    /** The list with [people] from [top] (pixels) down, one row each. */
    fun list(
        people: List<String>,
        top: Int,
    ): AccessibilityNodeInfo {
        likes.clear()
        rows.clear()
        val list =
            node("androidx.recyclerview.widget.RecyclerView", Rect(0, LIST_TOP, 1000, LIST_BOTTOM)) {
                isScrollable = true
                addAction(AccessibilityAction.ACTION_SCROLL_DOWN)
                viewIdResourceName = "$APP:id/feed"
            }
        people.forEachIndexed { index, person ->
            val y = top + index * ROW_PX
            val like = button("Like", Rect(700, y, 1000, y + ROW_PX), viewId = "like")
            val row =
                node("android.widget.LinearLayout", Rect(0, y, 1000, y + ROW_PX)) {
                    isClickable = clickableRows
                }.with(node("android.widget.TextView", Rect(0, y, 600, y + ROW_PX)) { text = person }, like)
            likes[person] = like
            rows[person] = row
            list.with(row)
        }
        return list
    }

    companion object {
        const val LIST_TOP = 200
        const val LIST_BOTTOM = 1800
    }
}

/** The "Like" element of [person]'s row in this snapshot (rows are at most [ROW_PX] high, found by its text). */
internal fun ScreenSnapshot.likeOf(person: String): UiElement {
    val text = elements.single { it.name == person }
    return elements.single { it.name == "Like" && it.bounds.top == text.bounds.top }
}

/** The element with [ref], or `null`. */
internal fun ScreenSnapshot.at(ref: ElementRef): UiElement? = elements.firstOrNull { it.ref == ref }

/** Whether ACTION_CLICK reached [node] (or a copy of it). */
internal val AccessibilityNodeInfo.clicked: Boolean get() = AccessibilityNodeInfo.ACTION_CLICK in log.ids

/** A content change somewhere on the main display. */
internal fun contentChanged(pkg: String = APP): A11yEvent =
    A11yEvent(0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, pkg, null)
