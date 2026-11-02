package dev.phoneagent.privileged

/**
 * Result of `IPrivilegedCore.startActivity` (docs/design/phase1-architecture.md §3.5).
 *
 * Anything other than [OK] and [COLD_IGNORED] means nothing was started. The helper never starts
 * an app the user is currently using on the main screen: that would pull the user's screen away.
 */
public object StartResult {
    /** Started on the requested display. */
    public const val OK: Int = 0

    /** The user has this app visible on the main screen; nothing was started. */
    public const val IN_USE_ON_MAIN: Int = 1

    /** Nothing can handle the intent (no launcher entry, no matching activity, no such component). */
    public const val NOT_FOUND: Int = 2

    /**
     * The helper could not inspect or start it; the reason is in logcat. This includes an intent
     * the `am start` fallback cannot carry: extras (protocol 4) or ClipData (protocol 6).
     */
    public const val ERROR: Int = 3

    /** Started, but the requested cold start was downgraded: the app has tasks elsewhere. */
    public const val COLD_IGNORED: Int = 4

    /** Another agent display already runs this app (a parallel task owns it). */
    public const val IN_USE_BY_AGENT: Int = 5

    /**
     * Refused by the helper: not its own display, another user, a malformed intent, or (protocol
     * 2) an intent that may carry another intent (from protocol 4: any extra of a type not on the
     * helper's list), has several handlers, or names an activity the app itself could not start
     * (not exported, or guarded by a permission the app lacks). From protocol 6 the last is the
     * activity manager's whole check for the app's uid: the application's permission when the
     * activity declares none, the app op of that permission, and the runtime permission an action
     * such as ACTION_CALL is tied to.
     */
    public const val REFUSED: Int = 6

    /**
     * The phone is locked, or the helper could not tell whether it is: nothing is started while
     * the user is away (protocol 2). From protocol 4, `probe()` reports the second case under
     * [ProbeKeys.Step.LOCK].
     */
    public const val LOCKED: Int = 7
}

/**
 * Negative results of `IPrivilegedCore.createDisplay`; zero or above is the new display id.
 * The reason is always in logcat, and `probe()` reports whether displays work at all.
 */
public object DisplayResult {
    /** The surface, size or density was not acceptable, or (protocol 3) the API level is below 34. */
    public const val REFUSED: Int = -2

    /**
     * The platform refused to create the display, or (protocol 3) its keyboard would have opened
     * on the user's screen, or (protocol 4) its IME policy could not be read back. Such a display
     * is released again; if that release fails it waits for `releaseAll` and no capability
     * accepts it.
     */
    public const val FAILED: Int = -1
}

/** Result of `IPrivilegedCore.grantSelf`. */
public object GrantResult {
    /** The app holds the permission now. */
    public const val GRANTED: Int = 0

    /** Not one of the permissions the helper may grant. */
    public const val NOT_ALLOWED: Int = 1

    /** The grant failed or could not be confirmed; the reason is in logcat. */
    public const val FAILED: Int = 2
}

/**
 * Keys of the `Bundle` returned by `IPrivilegedCore.probe`: [STEPS] and [RESULTS] are parallel
 * arrays, one entry per capability that was exercised, in the order they ran. A result is
 * [RESULT_OK] or a one-line reason why that capability is unavailable on this device.
 */
public object ProbeKeys {
    /** Every step succeeded; from protocol 4 this includes reading the lock state ([Step.LOCK]). */
    public const val OK: String = "ok"

    /** Names of the steps that ran, in order. */
    public const val STEPS: String = "steps"

    /** [RESULT_OK] or the failure reason, one per step. */
    public const val RESULTS: String = "results"

    /** Value in [RESULTS] for a step that worked. */
    public const val RESULT_OK: String = "ok"

    /** Step names, in the order [STEPS] lists them. */
    public object Step {
        /** Creating the virtual display. */
        public const val DISPLAY: String = "display"

        /** Keeping the IME on the agent display. */
        public const val IME: String = "ime"

        /** Reading the task list. */
        public const val TASKS: String = "tasks"

        /** Injecting one harmless tap. */
        public const val INPUT: String = "input"

        /** Watching the main screen. */
        public const val MAIN_SCREEN: String = "mainScreen"

        /**
         * Reading whether the phone is locked (protocol 4). Locked or not is `ok`; only a lock
         * state that cannot be read fails, because `startActivity` then answers [StartResult.LOCKED].
         */
        public const val LOCK: String = "lock"

        /**
         * Releasing the virtual display again. From protocol 5 a release that fails is reported
         * here (the display then waits for `releaseAll`); before, this step was always `ok`.
         */
        public const val RELEASE: String = "release"
    }
}

/**
 * Keys of the `Bundle` returned by `IPrivilegedCore.mainArrivals`: parallel arrays, one entry per
 * task that surfaced on the main screen, oldest first (a `Bundle` has no list of objects).
 */
public object ArrivalKeys {
    /** `int[]` task ids. */
    public const val TASK: String = "task"

    /** `String[]` package of the task's top activity; an entry may be null. */
    public const val PACKAGE: String = "package"

    /** `String[]` class name of the task's top activity; an entry may be null. */
    public const val ACTIVITY: String = "activity"

    /** `int[]` user the task belongs to, -1 when unknown. */
    public const val USER: String = "user"

    /** `long[]` `SystemClock.uptimeMillis` when the task was first seen on top. */
    public const val AT: String = "at"

    /** `int[]` display the task was on before, [FROM_NEW] when it was not seen before. */
    public const val FROM: String = "from"

    /** `boolean[]` the same task stayed on top and only its top activity changed. */
    public const val SAME_TASK: String = "sameTask"

    /** `boolean[]` an ordinary app activity (not launcher, recents or assistant). */
    public const val STANDARD: String = "standard"

    /** `int[]` display the task is on right now, -1 when it is gone. */
    public const val NOW: String = "now"

    /** `boolean` the window was watched through the task-stack listener instead of polling. */
    public const val LISTENING: String = "listening"

    /** Value in [FROM] for a task that was not seen on any display before. */
    public const val FROM_NEW: Int = -1
}
