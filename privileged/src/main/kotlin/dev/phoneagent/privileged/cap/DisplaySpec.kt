package dev.phoneagent.privileged.cap

/**
 * Flags for the agent's virtual display (docs/research/phone-agent-tech-routes.md §2.4).
 *
 * The set grows with the API level; every flag is named here because several are hidden constants.
 * Pure logic so the per-version set is unit-tested instead of discovered on a device.
 */
internal object DisplaySpec {
    /** `DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC`: other apps may read and target it. */
    const val PUBLIC = 1 shl 0

    /** `..._OWN_CONTENT_ONLY`: mirrors nothing; only what is launched on it. */
    const val OWN_CONTENT_ONLY = 1 shl 3

    /** `..._SUPPORTS_TOUCH`: accepts injected touches. */
    const val SUPPORTS_TOUCH = 1 shl 6

    /** `..._ROTATES_WITH_CONTENT`: the app decides the orientation, not the phone's sensors. */
    const val ROTATES_WITH_CONTENT = 1 shl 7

    /** `..._DESTROY_CONTENT_ON_REMOVAL`: tasks die with the display instead of moving to the main screen. */
    const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8

    /** `..._TRUSTED` (API 33+): required for input injection and for the focus flags. */
    const val TRUSTED = 1 shl 10

    /** `..._OWN_DISPLAY_GROUP` (API 33+): its own display group, so it does not follow the main screen. */
    const val OWN_DISPLAY_GROUP = 1 shl 11

    /** `..._ALWAYS_UNLOCKED` (API 33+): keeps running while the phone is locked. */
    const val ALWAYS_UNLOCKED = 1 shl 12

    /** `..._TOUCH_FEEDBACK_DISABLED` (API 33+): no vibration or sound from injected touches. */
    const val TOUCH_FEEDBACK_DISABLED = 1 shl 13

    /** `..._OWN_FOCUS` (API 34+): has its own focused window. */
    const val OWN_FOCUS = 1 shl 14

    /** `..._STEAL_TOP_FOCUS_DISABLED` (API 34+): never takes focus away from the user's screen. */
    const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    /** `WindowManager.DISPLAY_IME_POLICY_LOCAL`: the keyboard shows on the agent display, not the user's. */
    const val IME_POLICY_LOCAL = 0

    /** Smallest display the helper will create, in pixels; also the lower bound for [problem]. */
    const val MIN_EDGE = 64

    /** Largest display the helper will create, in pixels. */
    const val MAX_EDGE = 4_096

    /** Allowed density range in dpi. */
    private val DPI_RANGE = 60..1_200

    /**
     * Flags for [sdkInt]. API 34+ adds [OWN_FOCUS] and [STEAL_TOP_FOCUS_DISABLED], which together
     * keep the user's focus and keyboard on the main screen; both need [TRUSTED], so they are only
     * added on top of the API 33 set (phase1-architecture D2: background displays need API 34+).
     */
    fun flagsFor(sdkInt: Int): Int {
        var flags = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL
        if (sdkInt >= API_33) {
            flags = flags or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        }
        if (sdkInt >= API_34) flags = flags or OWN_FOCUS or STEAL_TOP_FOCUS_DISABLED
        return flags
    }

    /** Whether a display created for [sdkInt] keeps the user's focus and keyboard on the main screen. */
    fun keepsUserFocus(sdkInt: Int): Boolean = sdkInt >= API_34

    /** Why [width] x [height] @ [dpi] is not a display the helper will create, or null when it is fine. */
    fun problem(
        width: Int,
        height: Int,
        dpi: Int,
    ): String? =
        when {
            width !in MIN_EDGE..MAX_EDGE || height !in MIN_EDGE..MAX_EDGE -> {
                "display size must be between $MIN_EDGE and $MAX_EDGE px, was ${width}x$height"
            }

            dpi !in DPI_RANGE -> {
                "display density must be in $DPI_RANGE dpi, was $dpi"
            }

            else -> {
                null
            }
        }

    private const val API_33 = 33
    private const val API_34 = 34
}
