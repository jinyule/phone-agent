package dev.phoneagent.core.ports

import dev.phoneagent.core.ids.SnapshotId
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Normalized screen coordinates shared by [Bounds], [Target.Point] and the model-facing screen
 * format (phase1 §4): both axes run from 0 to [MAX] inclusive, independent of the display size.
 */
public object ScreenCoordinates {
    /** Largest coordinate on either axis. */
    public const val MAX: Int = 999

    /** Whether [value] is a valid coordinate. */
    public fun isValid(value: Int): Boolean = value in 0..MAX
}

/** Which display a [ScreenPort] drives. */
@Serializable
public data class SurfaceRef(
    /** Android display id. */
    val displayId: Int,
    /** Whether this is the agent's background display or the user's main display. */
    val kind: SurfaceKind,
)

/** Kind of display behind a [SurfaceRef]. */
@Serializable
public enum class SurfaceKind {
    /** Background virtual display owned by one task (D5). */
    AGENT,

    /** The physical screen the user is looking at. */
    MAIN,
}

/**
 * One observation of a surface (phase1 §3.3). Immutable; actions name the [id] they were planned on.
 *
 * @property id unique within the task; the screen format prints it (`screen s12`).
 * @property surface the display observed.
 * @property pkg foreground package, or `null` when the window has none (e.g. a system overlay).
 * @property activity foreground activity class, or `null` when unknown.
 * @property elements accessibility elements in reading order; refs are unique within the snapshot.
 * @property ocr text found by OCR that the tree lacks; `null` = OCR not run, empty = ran and found nothing new.
 * @property quality how much the tree can be trusted; decides whether a frame is attached.
 * @property flags page-level facts the formatter, policy and loop react to.
 * @property fingerprint stable digest of window, roles, names and bounds; equal fingerprints = no visible change.
 * @property detail the detail the port actually read this screen at, filled in by the port: for
 *   [ScreenPort.snapshot] the one asked for, for a read-back after an action or launch the one the
 *   port read it at. Metadata for the runtime (a re-check reads its fresh screen at the detail of
 *   the screen the call was planned on); the screen format does not print it. Left out of the
 *   encoding when [SnapshotDetail.BRIEF], so a log without it reads as BRIEF.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class ScreenSnapshot(
    val id: SnapshotId,
    val surface: SurfaceRef,
    val pkg: String?,
    val activity: String?,
    val elements: List<UiElement>,
    val ocr: List<OcrLine>?,
    val quality: TreeQuality,
    val flags: Set<ScreenFlag>,
    val fingerprint: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val detail: SnapshotDetail = SnapshotDetail.BRIEF,
) {
    init {
        val duplicate =
            elements
                .groupingBy { it.ref }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(duplicate.isEmpty()) { "Snapshot $id has duplicate element refs: $duplicate" }
    }
}

/**
 * Handle of an element inside one snapshot, written without the `@` the screen format prints
 * (`e12` is shown as `@e12`): a lowercase prefix and a positive number.
 */
@Serializable
@JvmInline
public value class ElementRef(
    /** E.g. `e12`; prefix `[a-z]+`, number without leading zeros. */
    public val value: String,
) {
    init {
        require(ELEMENT_REF.matches(value)) { "ElementRef must look like 'e12' (no '@'), got '$value'" }
    }

    override fun toString(): String = value
}

private val ELEMENT_REF = Regex("[a-z]+[1-9][0-9]*")

/**
 * One accessibility element, already normalized for the model.
 *
 * @property ref handle within the snapshot.
 * @property role coarse kind used by the formatter and policy.
 * @property name accessible name or visible label; `null` when the element has none.
 * @property value current text of editable or stateful elements; always `null` when [secret].
 * @property hint placeholder or hint text; `null` when none.
 * @property bounds position in normalized coordinates.
 * @property states interaction states.
 * @property secret password-like field: content never leaves the device, only this flag.
 * @property parent enclosing element in this snapshot (e.g. the scrollable list), or `null` at top level.
 * @property window window the element belongs to, for stale-ref matching; `null` when unknown.
 * @property resourceId Android view id name, or `null`.
 */
@Serializable
public data class UiElement(
    val ref: ElementRef,
    val role: ElementRole,
    val name: String? = null,
    val value: String? = null,
    val hint: String? = null,
    val bounds: Bounds,
    val states: Set<ElementState> = emptySet(),
    val secret: Boolean = false,
    val parent: ElementRef? = null,
    val window: Int? = null,
    val resourceId: String? = null,
) {
    init {
        require(!secret || value == null) { "Secret element $ref must not carry a value" }
    }
}

/** Coarse element kinds; the formatter localizes them and the policy keys rules on them. */
@Serializable
public enum class ElementRole {
    /** Clickable control. */
    BUTTON,

    /** Static text. */
    TEXT,

    /** Editable text field. */
    INPUT,

    /** Image or icon without text. */
    IMAGE,

    /** Checkbox. */
    CHECKBOX,

    /** On/off switch. */
    SWITCH,

    /** Radio button. */
    RADIO,

    /** Scrollable list or grid. */
    LIST,

    /** Tab in a tab bar. */
    TAB,

    /** Hyperlink. */
    LINK,

    /** Wheel or spinner picker (target of [UiAction.SetWheel]). */
    PICKER,

    /** Progress indicator. */
    PROGRESS,

    /** Layout container that is kept only because it groups children. */
    CONTAINER,

    /** Anything else. */
    OTHER,
}

/** Interaction states of a [UiElement]. */
@Serializable
public enum class ElementState {
    /** Responds to tap. */
    CLICKABLE,

    /** Responds to long press. */
    LONG_CLICKABLE,

    /** Accepts text input. */
    EDITABLE,

    /** Scrolls. */
    SCROLLABLE,

    /** Has input focus. */
    FOCUSED,

    /** Checked or switched on. */
    CHECKED,

    /** Selected (e.g. the current tab). */
    SELECTED,

    /** Present but disabled. */
    DISABLED,
}

/** Axis-aligned box in normalized coordinates, edges inclusive. */
@Serializable
public data class Bounds(
    /** Left edge, 0–999. */
    val left: Int,
    /** Top edge, 0–999. */
    val top: Int,
    /** Right edge, `left`–999. */
    val right: Int,
    /** Bottom edge, `top`–999. */
    val bottom: Int,
) {
    init {
        require(
            ScreenCoordinates.isValid(left) &&
                ScreenCoordinates.isValid(top) &&
                ScreenCoordinates.isValid(right) &&
                ScreenCoordinates.isValid(bottom) &&
                left <= right &&
                top <= bottom,
        ) { "Invalid bounds [$left,$top,$right,$bottom]" }
    }

    /** The center point, rounded down. */
    public fun center(): Target.Point = Target.Point((left + right) / 2, (top + bottom) / 2)
}

/**
 * A line of text recognized by OCR.
 *
 * @property text recognized text.
 * @property bounds position in normalized coordinates.
 * @property confidence recognizer confidence in 0–1, or `null` when the recognizer reports none.
 */
@Serializable
public data class OcrLine(
    val text: String,
    val bounds: Bounds,
    val confidence: Double? = null,
) {
    init {
        require(confidence == null || confidence in 0.0..1.0) { "OCR confidence must be in 0..1, got $confidence" }
    }
}

/** How usable the accessibility tree is (decides whether a frame is attached, phase1 D6). */
@Serializable
public enum class TreeQuality {
    /** Enough named elements to act on. */
    GOOD,

    /** Some elements, but large unlabeled areas (games, canvases, WebViews without a11y). */
    SPARSE,

    /** No usable elements. */
    EMPTY,
}

/** Page-level facts derived from the tree, OCR and window state. */
@Serializable
public enum class ScreenFlag {
    /** FLAG_SECURE page: frames are black, never send them. */
    SECURE,

    /** A soft keyboard is showing. */
    KEYBOARD,

    /** A system picker or chooser (share sheet, file picker, permission dialog) is in front. */
    SYSTEM_PICKER,

    /** A login page. */
    LOGIN,

    /** A captcha or human verification. */
    CAPTCHA,

    /** A payment or checkout page. */
    PAYMENT,

    /** The app says the user is acting too fast or is rate limited. */
    RATE_LIMIT,

    /** The device is locked or the screen is off (D11: tasks pause). */
    LOCKED,
}

/**
 * Change between two snapshots of the same surface as the model reads them (phase1 §4): by the
 * lines of the screen format (one per printed element, with the texts a row absorbs) and by the
 * OCR lines. Applying it to what the model saw of the earlier snapshot gives what it would see of
 * the later one: an element in none of the lists still shows the same line under the same ref.
 *
 * Both snapshots come from the same [ScreenPort], so an equal ref is the same element; elements
 * are paired only by ref, and a ref that changes is one element removed and one added.
 *
 * @property added printed elements whose ref the earlier snapshot did not show (`+`), as they are now.
 * @property removed refs the earlier snapshot showed that the later one no longer shows: the
 *   element went away, was hidden or absorbed, got a new ref, or its ref went to an element that
 *   is not shown (`-`). A ref of [added] or [changed] is never in it.
 * @property changed printed elements shown under the same ref in both snapshots that read
 *   differently (text, absorbed texts, value, hint, states, printed coordinates) or moved to
 *   another parent, as they are now (`~`).
 * OCR lines are compared only when the later snapshot was read with OCR (`ocr` not `null`): a
 * later read without OCR says nothing about the earlier lines, so none of them is removed.
 *
 * @property ocrAdded OCR lines of the later snapshot whose text or place is new; with no OCR on
 *   the earlier snapshot, all of its lines.
 * @property ocrRemoved OCR lines of the earlier snapshot that are gone from the later one's OCR; a
 *   line that moved is in both lists (removed at its old place, added at its new one).
 * @property ocrUnverified the earlier snapshot had OCR lines and the later one was read without
 *   OCR, so whether they are still there, and where, is not known. Not a change: [isEmpty] does
 *   not look at it.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class ScreenDiff(
    val added: List<UiElement> = emptyList(),
    val removed: List<ElementRef> = emptyList(),
    val changed: List<UiElement> = emptyList(),
    // Left out when empty or false, so logs written before these fields existed read and write the same.
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ocrAdded: List<OcrLine> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ocrRemoved: List<OcrLine> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val ocrUnverified: Boolean = false,
) {
    /** Whether nothing is known to have changed ([ocrUnverified] aside). */
    public val isEmpty: Boolean
        get() = added.isEmpty() && removed.isEmpty() && changed.isEmpty() && ocrAdded.isEmpty() && ocrRemoved.isEmpty()
}
