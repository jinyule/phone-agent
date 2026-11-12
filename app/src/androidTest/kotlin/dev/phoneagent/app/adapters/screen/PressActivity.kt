package dev.phoneagent.app.adapters.screen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.phoneagent.app.test.R

/**
 * A page of the test APK for presses by ref (fix list fwd S1): a clickable parent
 * (`press_parent`) holds a wrapper that takes nothing (`press_wrapper`), which holds a clickable
 * child (`press_child`) holding the text pressed (`press_label`, "Details"). Parent and child each
 * count their clicks and long clicks, whatever made them (an accessibility action or a touch), in
 * texts above them (`parent_clicks`, `parent_long_clicks`, `child_clicks`, `child_long_clicks`).
 *
 * Extras: [RUN], a text the page shows (`press_run`) to tell it from the page it replaced;
 * [CHILD_ENABLED] (default `true`); [CHILD_REFUSES], the child answers the accessibility click and
 * long click `false` without making them, as a view that takes only touches does. A new intent
 * with [ENABLE_CHILD] `true` enables the child.
 *
 * It runs in the test APK's own process, and the test APK carries no Kotlin standard library (the
 * app's is left out of it): nothing here calls into it, so no `lateinit`, no `listOf`, and
 * parameters of non-private functions nullable (no null checks generated), as in [FieldActivity].
 */
class PressActivity : Activity() {
    private var child: RefusingLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parentClicks = counter(R.id.parent_clicks)
        val parentLongClicks = counter(R.id.parent_long_clicks)
        val childClicks = counter(R.id.child_clicks)
        val childLongClicks = counter(R.id.child_long_clicks)
        val run =
            TextView(this).apply {
                id = R.id.press_run
                text = intent.getStringExtra(RUN)
            }
        val label =
            TextView(this).apply {
                id = R.id.press_label
                text = LABEL
                textSize = LABEL_SP
            }
        val child =
            RefusingLayout(this).apply {
                id = R.id.press_child
                gravity = Gravity.CENTER
                counts(childClicks, childLongClicks)
                isEnabled = intent.getBooleanExtra(CHILD_ENABLED, true)
                refuses = intent.getBooleanExtra(CHILD_REFUSES, false)
                addView(label, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
        this.child = child
        val wrapper =
            FrameLayout(this).apply {
                id = R.id.press_wrapper
                setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
                addView(child, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        val parent =
            LinearLayout(this).apply {
                id = R.id.press_parent
                setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
                counts(parentClicks, parentLongClicks)
                addView(wrapper, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        val page =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                // Drawn edge to edge: kept clear of the status bar, or the reader leaves the counts under it out.
                fitsSystemWindows = true
                for (counter in arrayOf(run, parentClicks, parentLongClicks, childClicks, childLongClicks)) {
                    addView(counter, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                }
                addView(parent, LinearLayout.LayoutParams(MATCH_PARENT, BOX_PX))
            }
        setContentView(page)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null && intent.getBooleanExtra(ENABLE_CHILD, false)) child?.isEnabled = true
    }

    private fun counter(viewId: Int): TextView =
        TextView(this).apply {
            id = viewId
            text = "0"
        }

    /** Makes this view take clicks and long clicks, counting them in [clicks] and [longClicks]. */
    private fun View.counts(
        clicks: TextView,
        longClicks: TextView,
    ) {
        setOnClickListener { clicks.text = "${clicks.text.toString().toInt() + 1}" }
        setOnLongClickListener {
            longClicks.text = "${longClicks.text.toString().toInt() + 1}"
            true
        }
    }

    /** A layout that, when it [refuses], answers the accessibility click and long click `false` without making them. */
    private class RefusingLayout(
        context: Context?,
    ) : LinearLayout(context) {
        var refuses = false

        override fun performAccessibilityAction(
            action: Int,
            arguments: Bundle?,
        ): Boolean {
            val press =
                action == AccessibilityNodeInfo.ACTION_CLICK || action == AccessibilityNodeInfo.ACTION_LONG_CLICK
            return if (refuses && press) false else super.performAccessibilityAction(action, arguments)
        }
    }

    companion object {
        /** The text pressed. */
        const val LABEL: String = "Details"

        /** Boolean extra: the child is enabled when the page opens (default `true`). */
        const val CHILD_ENABLED: String = "childEnabled"

        /** Boolean extra: the child answers the accessibility click and long click `false`. */
        const val CHILD_REFUSES: String = "childRefuses"

        /** String extra: the text of `press_run`. */
        const val RUN: String = "run"

        /** Boolean extra of a new intent: enable the child. */
        const val ENABLE_CHILD: String = "enableChild"

        private const val LABEL_SP = 20f
        private const val PADDING_PX = 48
        private const val BOX_PX = 900
    }
}
