/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * core/src/main/kotlin/com/wangzan101/mika/core/shell/HelperHandoff.kt, `check`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: split into pre-Binder, identity and version stages;
 * added the protocol and versionCode comparison.
 */
package dev.phoneagent.privileged.handoff

import dev.phoneagent.privileged.PrivilegedProtocol

/** What a helper claims about itself in the `attach` extras, before any of it is trusted. */
public data class HelperOffer(
    /** uid the helper reports; `-1` when absent. */
    val reportedUid: Int,
    /** pid the helper reports; `-1` when absent. */
    val reportedPid: Int,
    /** Whether the extras carry a Binder. */
    val hasBinder: Boolean,
    /** Presented nonce; null when absent. */
    val nonce: String?,
    /** APK the helper's code came from; null when absent. */
    val apkPath: String?,
    /** versionCode of that APK; null when absent. */
    val versionCode: Long?,
) {
    /** Omits the nonce. */
    override fun toString(): String {
        val presented = if (nonce == null) "absent" else "present"
        return "HelperOffer(uid=$reportedUid, pid=$reportedPid, binder=$hasBinder, nonce=$presented, " +
            "apk=$apkPath, versionCode=$versionCode)"
    }
}

/** The running app build a helper must match. */
public data class AppBuild(
    /** versionCode of the installed app. */
    val versionCode: Long,
    /** `ApplicationInfo.sourceDir` of the installed app; a new path means a new install. */
    val apkPath: String,
    /** Protocol compiled into the app. */
    val protocolVersion: Int = PrivilegedProtocol.VERSION,
)

/**
 * The app-side handoff checks, in the order the app must run them:
 * [precheck] (no Binder call), then the nonce, then [checkIdentity] (calls `identity()`),
 * then [checkVersion] (calls `protocolVersion()`). Each returns null when the check passes.
 */
public object HandoffGate {
    /** Checks that need neither the nonce store nor a call into the offered Binder. */
    public fun precheck(
        callingUid: Int,
        callingPid: Int,
        offer: HelperOffer,
    ): HandoffReject? =
        when {
            callingUid != HandoffContract.SHELL_UID -> HandoffReject.NOT_SHELL
            offer.reportedUid != callingUid || offer.reportedPid != callingPid -> HandoffReject.ID_MISMATCH
            !offer.hasBinder -> HandoffReject.NO_BINDER
            else -> null
        }

    /**
     * [identity] is what `identity()` returned on the offered Binder (null if the call failed).
     * It must start with the calling uid and pid: the Binder lives in the process that called.
     */
    public fun checkIdentity(
        identity: IntArray?,
        callingUid: Int,
        callingPid: Int,
    ): HandoffReject? =
        if (identity?.take(2) ==
            listOf(callingUid, callingPid)
        ) {
            null
        } else {
            HandoffReject.BINDER_ELSEWHERE
        }

    /**
     * [helperProtocol] is what `protocolVersion()` returned (null if the call failed). The helper
     * must speak the app's protocol and run the exact APK the app runs; a missing field counts as
     * a mismatch, so helpers that predate a field are restarted rather than trusted.
     */
    public fun checkVersion(
        helperProtocol: Int?,
        offer: HelperOffer,
        app: AppBuild,
    ): HandoffReject? =
        when {
            helperProtocol != app.protocolVersion -> HandoffReject.STALE_PROTOCOL
            offer.versionCode != app.versionCode || offer.apkPath != app.apkPath -> HandoffReject.STALE_APK
            else -> null
        }
}
