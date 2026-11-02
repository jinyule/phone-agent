package dev.phoneagent.app.adapters.screen

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import dev.phoneagent.core.ports.ElementRole
import dev.phoneagent.core.ports.ElementState

/** What one accessibility node says about itself: role, states, interactivity. */
internal object NodeFacts {
    private const val CHROME_ROLE = "AccessibilityNodeInfo.chromeRole"
    private val LIST_CLASSES = listOf("RecyclerView", "ListView", "GridView", "ScrollView", "ViewPager")

    // Adapter views: every child is an item, however large.
    private val COLLECTION_CLASSES = listOf("RecyclerView", "ListView", "GridView", "ViewPager", "ViewPager2")
    private val SWITCH_CLASSES = listOf("Switch", "SwitchCompat", "SwitchMaterial")

    /** Role from the widget class alone, or `null` when the class says nothing. */
    private val CLASS_ROLES: List<Pair<(String) -> Boolean, ElementRole>> =
        listOf(
            { cls: String -> cls.endsWith("EditText") } to ElementRole.INPUT,
            { cls: String -> SWITCH_CLASSES.any(cls::endsWith) } to ElementRole.SWITCH,
            { cls: String -> cls.endsWith("RadioButton") } to ElementRole.RADIO,
            { cls: String -> cls.endsWith("CheckBox") } to ElementRole.CHECKBOX,
            { cls: String -> cls.endsWith("NumberPicker") } to ElementRole.PICKER,
            { cls: String -> cls.endsWith("ProgressBar") } to ElementRole.PROGRESS,
            { cls: String -> cls.endsWith("TabView") } to ElementRole.TAB,
        )

    /** Coarse role of [node]: editability and widget class first, then interaction, then content. */
    fun roleOf(node: AccessibilityNodeInfo): ElementRole {
        val cls = node.className?.toString().orEmpty()
        if (node.isEditable) return ElementRole.INPUT
        return CLASS_ROLES.firstOrNull { (test, _) -> test(cls) }?.second ?: behaviourRole(node, cls)
    }

    private fun behaviourRole(
        node: AccessibilityNodeInfo,
        cls: String,
    ): ElementRole =
        when {
            node.isCheckable -> ElementRole.CHECKBOX
            node.extras?.getCharSequence(CHROME_ROLE)?.toString() == "link" -> ElementRole.LINK
            isList(node, cls) -> ElementRole.LIST
            cls.endsWith("Button") || node.isClickable -> ElementRole.BUTTON
            cls.endsWith("ImageView") -> ElementRole.IMAGE
            !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() -> ElementRole.TEXT
            node.childCount > 0 -> ElementRole.CONTAINER
            else -> ElementRole.OTHER
        }

    private fun isList(
        node: AccessibilityNodeInfo,
        cls: String,
    ): Boolean = node.isScrollable || node.collectionInfo != null || LIST_CLASSES.any(cls::endsWith)

    /** What [node] (of role [role]) is for rows ([Identities.of]). */
    fun shapeOf(
        node: AccessibilityNodeInfo,
        role: ElementRole,
    ): Identities.Shape {
        val cls = node.className?.toString().orEmpty()
        val list =
            when {
                role != ElementRole.LIST -> null
                node.collectionInfo != null || COLLECTION_CLASSES.any(cls::endsWith) -> Identities.ListKind.COLLECTION
                cls.endsWith("ScrollView") -> Identities.ListKind.WRAPPED
                else -> Identities.ListKind.PLAIN
            }
        val interactive = node.isClickable || node.isLongClickable || node.isCheckable
        return Identities.Shape(list, item = node.collectionItemInfo != null, interactive = interactive)
    }

    /** Interaction states of [node]. */
    fun statesOf(node: AccessibilityNodeInfo): Set<ElementState> =
        buildSet {
            if (node.isClickable) add(ElementState.CLICKABLE)
            if (node.isLongClickable) add(ElementState.LONG_CLICKABLE)
            if (node.isEditable) add(ElementState.EDITABLE)
            if (node.isScrollable) add(ElementState.SCROLLABLE)
            if (node.isFocused) add(ElementState.FOCUSED)
            if (node.isCheckable && isChecked(node)) add(ElementState.CHECKED)
            if (node.isSelected) add(ElementState.SELECTED)
            if (!node.isEnabled) add(ElementState.DISABLED)
        }

    /** Whether [node] reacts to the user: click, long-click, text, scroll, check or a range. */
    fun interactive(node: AccessibilityNodeInfo): Boolean =
        node.isClickable ||
            node.isLongClickable ||
            node.isEditable ||
            node.isScrollable ||
            node.isCheckable ||
            node.rangeInfo != null

    /** Whether [node] is checked; API 36 replaced the boolean with a tri-state. */
    fun isChecked(node: AccessibilityNodeInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            node.checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        } else {
            @Suppress("DEPRECATION")
            node.isChecked
        }
}

/**
 * The text facts of one node: name, value, hint (phase1 §4: secrets never leave the device).
 * The hint-as-text check and the descendant label are adapted from Mika's ScreenReader
 * (Apache-2.0, see NOTICE). Modified by PhoneAgent contributors.
 */
internal object NodeText {
    /** Longest name or value kept; longer text ends with an ellipsis. */
    const val MAX_TEXT: Int = 500

    /** Longest name a clickable container takes from its descendants. */
    const val MAX_AGGREGATE: Int = 120

    private const val AGGREGATE_DEPTH = 6
    private const val PERCENT = 100

    /** Accessible name of [node] with role [role]; a clickable container without one takes its descendants' text. */
    fun nameOf(
        node: AccessibilityNodeInfo,
        role: ElementRole,
    ): String? {
        if (role == ElementRole.INPUT) return clean(node.contentDescription) ?: labelOf(node)
        val actionable = node.isClickable || node.isLongClickable || node.isCheckable
        return ownLabel(node) ?: if (actionable && role != ElementRole.LIST) descendantLabel(node) else null
    }

    /**
     * The name [node] has in its [ElementIdentity]: [nameOf] at any length, without the text of
     * visible descendants a clickable container borrows (it changes as they scroll out of view;
     * the container's row holds all of them).
     */
    fun identityNameOf(
        node: AccessibilityNodeInfo,
        role: ElementRole,
    ): String? = if (role == ElementRole.INPUT) nameOf(node, role) else identityLabel(node)

    /** Current text of an editable [node], or the range position of a progress or slider; `null` for passwords. */
    fun valueOf(
        node: AccessibilityNodeInfo,
        role: ElementRole,
    ): String? {
        val range = node.rangeInfo
        return when {
            node.isPassword -> null
            role == ElementRole.INPUT -> fieldText(node)?.let(::cap)
            range == null -> null
            range.type == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT -> "${range.current.toInt()}%"
            range.type == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT -> range.current.toInt().toString()
            range.max > range.min -> "${((range.current - range.min) / (range.max - range.min) * PERCENT).toInt()}%"
            else -> range.current.toString()
        }
    }

    /** Placeholder of [node]: its hint text, or the text an editable field shows as hint. */
    fun hintOf(node: AccessibilityNodeInfo): String? =
        clean(node.hintText) ?: if (node.isEditable && node.isShowingHintText) clean(node.text) else null

    /** What the user sees as text in an editable [node]: empty while it shows its hint; `null` for password fields. */
    fun fieldText(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()
        val hint = node.hintText?.toString()?.trim()
        return when {
            node.isPassword -> null

            node.isShowingHintText || text == null -> ""

            // Some fields report their hint as text without the showing-hint flag.
            !hint.isNullOrEmpty() && text.trim() == hint -> ""

            else -> text
        }
    }

    /** The node's own label: text, else content description; editable and password nodes only the description. */
    fun ownLabel(node: AccessibilityNodeInfo): String? = identityLabel(node)?.let(::cap)

    /** [ownLabel] at any length: what tells elements apart (never shown, never logged). */
    fun identityLabel(node: AccessibilityNodeInfo): String? =
        if (node.isPassword || node.isEditable) {
            whole(node.contentDescription)
        } else {
            whole(node.text) ?: whole(node.contentDescription)
        }

    private fun clean(text: CharSequence?): String? = whole(text)?.let(::cap)

    /**
     * Text of the node labelling [node] (`android:labelFor`); API 36 allows several and deprecated
     * the single getter.
     */
    private fun labelOf(node: AccessibilityNodeInfo): String? {
        val label =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                node.labeledByList.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                node.labeledBy
            }
        return label?.let { clean(it.text) ?: clean(it.contentDescription) }
    }

    /** Text of visible descendants, for a clickable container without its own label. */
    private fun descendantLabel(node: AccessibilityNodeInfo): String? {
        val parts = LinkedHashSet<String>()
        collectLabels(node, depth = 0, parts)
        val joined = parts.joinToString(" ")
        return when {
            joined.isEmpty() -> null
            joined.length > MAX_AGGREGATE -> joined.take(MAX_AGGREGATE) + "…"
            else -> joined
        }
    }

    private fun collectLabels(
        parent: AccessibilityNodeInfo,
        depth: Int,
        parts: MutableSet<String>,
    ) {
        if (depth > AGGREGATE_DEPTH || parts.sumOf { it.length + 1 } > MAX_AGGREGATE) return
        val children = (0 until parent.childCount).mapNotNull(parent::getChild).filter { it.isVisibleToUser }
        for (child in children) {
            ownLabel(child)?.let(parts::add)
            collectLabels(child, depth + 1, parts)
        }
    }
}

/** [text] cut to [NodeText.MAX_TEXT], ending with an ellipsis when cut. */
private fun cap(text: String): String =
    if (text.length >
        NodeText.MAX_TEXT
    ) {
        text.take(NodeText.MAX_TEXT) + "…"
    } else {
        text
    }

/** [text] trimmed, `null` when nothing is left. */
private fun whole(text: CharSequence?): String? =
    text
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
