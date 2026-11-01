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
 * `releaseAll()`; the rejection then makes it exit. Admission uses up the nonce and issues the next one.
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

        data class Admitted(
            val core: IPrivilegedCore,
            val binder: IBinder,
            val nextNonce: String,
        ) : Decision {
            override fun toString(): String = "Admitted"
        }
    }

    fun decide(
        extras: Bundle?,
        callingUid: Int,
        callingPid: Int,
    ): Decision {
        val offer = HandoffBundles.readOffer(extras)
        val binder = HandoffBundles.binder(extras)
        val early =
            HandoffGate.precheck(callingUid, callingPid, offer)
                ?: HandoffReject.BAD_NONCE.takeIf { nonces.match(offer.nonce) == null }
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
        val stale =
            elsewhere ?: HandoffGate.checkVersion(ask("protocolVersion") { core.protocolVersion() }, offer, appBuild())
        return when {
            elsewhere != null -> {
                rejected(elsewhere, offer)
            }

            stale != null -> {
                release(stale, core, offer, callingPid)
            }

            else -> {
                nonces.consume(offer.nonce)
                Decision.Admitted(core, binder, nonces.issueResume())
            }
        }
    }

    private fun release(
        reason: HandoffReject,
        core: IPrivilegedCore,
        offer: HelperOffer,
        pid: Int,
    ): Decision {
        nonces.consume(offer.nonce)
        try {
            core.releaseAll()
        } catch (e: RemoteException) {
            Log.w(TAG, "stale helper $pid did not take releaseAll; it exits on the rejection anyway", e)
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

    /** One Binder call into a not yet trusted helper; null when it fails or refuses. */
    private fun <T> ask(
        what: String,
        call: () -> T,
    ): T? =
        try {
            call()
        } catch (e: RemoteException) {
            Log.w(TAG, "$what on the offered helper failed", e)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "$what on the offered helper was refused", e)
            null
        }

    private companion object {
        const val TAG = "PhoneAgentHelperClient"
    }
}
