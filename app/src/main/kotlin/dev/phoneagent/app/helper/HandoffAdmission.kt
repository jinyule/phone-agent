/*
 * Portions adapted from Mika (https://github.com/wangzan101/Mika, commit 9cef375,
 * app/src/main/java/com/wangzan101/mika/cap/phone/shell/HelperHub.kt, `offer`).
 * Copyright 2026 wangzan101. Licensed under the Apache License, Version 2.0.
 *
 * Modified by PhoneAgent contributors: protocol/versionCode/APK check with releaseAll of stale
 * helpers; the decision is separate from the connection state.
 */
package dev.phoneagent.app.helper

import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import dev.phoneagent.privileged.IPrivilegedCore
import dev.phoneagent.privileged.handoff.AppBuild
import dev.phoneagent.privileged.handoff.HandoffBundles
import dev.phoneagent.privileged.handoff.HandoffGate
import dev.phoneagent.privileged.handoff.HandoffNonces
import dev.phoneagent.privileged.handoff.HandoffReject
import dev.phoneagent.privileged.handoff.HelperOffer

/**
 * Decides whether an offered helper is admitted (phase1-architecture §2.2), in this order:
 * caller is uid 2000 and reports its own uid/pid → the nonce is live (before touching the Binder)
 * → `identity()` names the calling process → `protocolVersion()`, versionCode and APK match this
 * build. A helper failing only the last check is stale: its nonce is burnt and it is told to
 * `releaseAll()`; the rejection then makes it exit. A failed `identity()` or `protocolVersion()`
 * call is [HandoffReject.BINDER_ELSEWHERE] and leaves the nonce alone; only a protocol version
 * that was read and differs is [HandoffReject.STALE_PROTOCOL].
 *
 * [decide] does not use up the nonce of an admitted helper: the caller first follows the helper's
 * death, then [commit]s, which uses up the nonce and issues the next resume nonce in one save
 * ([HandoffNonces.admit]). Until then nothing is written, so a handoff that fails late leaves the
 * connected helper's resume nonce alone; and the resume nonce from before stays valid for one more
 * admission, so the app may die anywhere in the commit.
 * A nonce that stopped being live while the helper was queried (expired, replaced by a new `arm`)
 * is refused as [HandoffReject.BAD_NONCE], for stale helpers too.
 *
 * Not thread-safe; [HelperClient] serializes calls.
 */
internal class HandoffAdmission(
    private val nonces: HandoffNonces,
    private val appBuild: () -> AppBuild,
) {
    sealed interface Decision {
        data class Rejected(
            val reason: HandoffReject,
        ) : Decision

        /** Already told to release; the caller rejects it with [reason]. */
        data class Stale(
            val reason: HandoffReject,
        ) : Decision

        /** The helper process [pid] passed every check with [nonce]; nothing is written until [commit]. */
        data class Admitted(
            val core: IPrivilegedCore,
            val binder: IBinder,
            val nonce: String?,
            val pid: Int,
        ) : Decision {
            override fun toString(): String = "Admitted"
        }
    }

    /**
     * Uses up the nonce of [admitted] and returns the helper's next resume nonce, or null when the
     * nonce is no longer live (the caller then refuses with [HandoffReject.BAD_NONCE]).
     */
    fun commit(admitted: Decision.Admitted): String? = nonces.admit(admitted.nonce, admitted.pid)

    fun decide(
        extras: Bundle?,
        callingUid: Int,
        callingPid: Int,
    ): Decision {
        val offer = HandoffBundles.readOffer(extras)
        val binder = HandoffBundles.binder(extras)
        val early =
            HandoffGate.precheck(callingUid, callingPid, offer)
                ?: HandoffReject.BAD_NONCE.takeIf { nonces.match(offer.nonce, callingPid) == null }
        return if (early == null && binder != null) {
            inspect(binder, offer, callingUid, callingPid)
        } else {
            rejected(early ?: HandoffReject.NO_BINDER, offer)
        }
    }

    /** The checks that call into the offered Binder. */
    private fun inspect(
        binder: IBinder,
        offer: HelperOffer,
        callingUid: Int,
        callingPid: Int,
    ): Decision {
        val core = IPrivilegedCore.Stub.asInterface(binder)
        val elsewhere = HandoffGate.checkIdentity(ask("identity") { core.identity() }, callingUid, callingPid)
        // A protocolVersion() call that fails is no version at all: a refusal like identity(), not a stale helper.
        val protocol = if (elsewhere == null) ask("protocolVersion") { core.protocolVersion() } else null
        val stale = protocol?.let { HandoffGate.checkVersion(it, offer, appBuild()) }
        return when {
            elsewhere != null || protocol == null -> {
                rejected(elsewhere ?: HandoffReject.BINDER_ELSEWHERE, offer)
            }

            stale != null -> {
                release(stale, core, offer, callingPid)
            }

            else -> {
                Decision.Admitted(core, binder, offer.nonce, callingPid)
            }
        }
    }

    // TooGenericExceptionCaught: the stale helper is untrusted and may throw anything; it must still be rejected.
    @Suppress("TooGenericExceptionCaught")
    private fun release(
        reason: HandoffReject,
        core: IPrivilegedCore,
        offer: HelperOffer,
        pid: Int,
    ): Decision {
        if (nonces.consume(offer.nonce, pid) == null) return rejected(HandoffReject.BAD_NONCE, offer)
        try {
            core.releaseAll()
        } catch (e: RemoteException) {
            Log.w(TAG, "stale helper $pid did not take releaseAll; it exits on the rejection anyway", e)
        } catch (e: RuntimeException) {
            // Parcel.readException rethrows whatever the helper threw (IllegalStateException, ...).
            Log.w(TAG, "stale helper $pid failed releaseAll; it releases again when it exits on the rejection", e)
        }
        Log.w(TAG, "stale helper $pid ($reason): $offer, app ${appBuild()}")
        return Decision.Stale(reason)
    }

    private fun rejected(
        reason: HandoffReject,
        offer: HelperOffer,
    ): Decision {
        Log.w(TAG, "helper handoff rejected ($reason): $offer")
        return Decision.Rejected(reason)
    }

    /**
     * One Binder call into a not yet trusted helper; null when it fails, refuses or throws.
     * Parcel.readException rethrows any RuntimeException of the remote side, not only SecurityException.
     */
    @Suppress("TooGenericExceptionCaught") // Any RuntimeException here is the untrusted helper refusing.
    private fun <T> ask(
        what: String,
        call: () -> T,
    ): T? =
        try {
            call()
        } catch (e: RemoteException) {
            Log.w(TAG, "$what on the offered helper failed", e)
            null
        } catch (e: RuntimeException) {
            Log.w(TAG, "$what on the offered helper threw", e)
            null
        }

    private companion object {
        const val TAG = "PhoneAgentHelperClient"
    }
}
