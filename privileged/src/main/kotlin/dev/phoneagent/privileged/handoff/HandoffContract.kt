/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * core/src/main/kotlin/com/wangzan101/mika/core/shell/HelperHandoff.kt).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: renamed the keys, added the protocol/versionCode fields
 * and the arm/status methods, split the checks into HandoffGate.
 */
package dev.phoneagent.privileged.handoff

/**
 * Names shared by the helper and the app for the Binder handoff (phase1-architecture §2.2):
 * the helper calls the app's ContentProvider `call(METHOD_ATTACH, extras)` with its Binder,
 * the app answers with [KEY_ACCEPTED] and, when accepted, the next nonce and its own token.
 */
public object HandoffContract {
    /** uid of `adb shell`; the only identity the app accepts a helper from. */
    public const val SHELL_UID: Int = 2000

    /** Fully qualified `app_process` entry point; scripts and the ADB launcher start this class. */
    public const val HELPER_MAIN_CLASS: String = "dev.phoneagent.privileged.helper.HelperMain"

    /** Helper → app: offers the helper Binder. Extras: the `KEY_*` values below. */
    public const val METHOD_ATTACH: String = "attach"

    /**
     * Shell → app: arms a launch nonce for a helper started from a computer. `arg` is the
     * lowercase hex SHA-256 of the nonce, so the nonce itself never appears on a command line.
     */
    public const val METHOD_ARM: String = "arm"

    /** Shell → app: current helper connection state ([KEY_STATE], [KEY_HELPER_PID]). */
    public const val METHOD_STATUS: String = "status"

    /** Nonce the helper presents (launch, armed or resume). */
    public const val KEY_NONCE: String = "nonce"

    /** The helper's `IPrivilegedCore` Binder. */
    public const val KEY_BINDER: String = "binder"

    /** uid the helper reports for itself; must equal the Binder calling uid. */
    public const val KEY_UID: String = "uid"

    /** pid the helper reports for itself; must equal the Binder calling pid. */
    public const val KEY_PID: String = "pid"

    /** Path of the APK the helper's code was loaded from (its `CLASSPATH`). */
    public const val KEY_APK: String = "apk"

    /** versionCode of the APK the helper's code was loaded from. */
    public const val KEY_VERSION_CODE: String = "versionCode"

    /** App → helper: whether the Binder was accepted. A rejected helper releases and exits. */
    public const val KEY_ACCEPTED: String = "accepted"

    /** App → helper: [HandoffReject] name when rejected. */
    public const val KEY_REASON: String = "reason"

    /** App → helper: the one-time nonce for the next handoff (after the app process restarts). */
    public const val KEY_NEXT_NONCE: String = "next"

    /** App → helper: a Binder living in the app process; its death tells the helper the app died. */
    public const val KEY_TOKEN: String = "token"

    /** Status reply: connection state name. */
    public const val KEY_STATE: String = "state"

    /** Status reply: pid of the connected (or last) helper, `-1` if none. */
    public const val KEY_HELPER_PID: String = "helperPid"

    private const val AUTHORITY_SUFFIX = ".helper"
    private const val PROCESS_SUFFIX = ":helper"

    /** Authority of the app's handoff provider. */
    public fun authority(packageName: String): String = packageName + AUTHORITY_SUFFIX

    /** `--nice-name` of the helper process, so it can be recognized in `ps`/`pidof`. */
    public fun processName(packageName: String): String = packageName + PROCESS_SUFFIX
}

/** Why the app refused a helper. The helper releases everything and exits on any of these. */
public enum class HandoffReject {
    /** The caller is not uid 2000. */
    NOT_SHELL,

    /** The uid/pid the helper reports differ from the Binder calling uid/pid. */
    ID_MISMATCH,

    /** No Binder in the extras. */
    NO_BINDER,

    /** The nonce is missing, expired, already used, or was never issued. */
    BAD_NONCE,

    /** `identity()` on the offered Binder does not name the calling process. */
    BINDER_ELSEWHERE,

    /** The helper speaks a different [dev.phoneagent.privileged.PrivilegedProtocol.VERSION]. */
    STALE_PROTOCOL,

    /** The helper runs code from another APK (the app was updated or reinstalled). */
    STALE_APK,
}
