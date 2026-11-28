package dev.phoneagent.privileged.handoff

import android.os.Bundle
import android.os.IBinder

/** Encodes and decodes the `attach` extras and reply (keys in [HandoffContract]). */
public object HandoffBundles {
    private const val ABSENT = -1

    /** The extras a helper sends with `attach`: its [claims] about itself, plus its [binder] (implies `hasBinder`). */
    public fun offer(
        binder: IBinder,
        claims: HelperOffer,
    ): Bundle =
        Bundle().apply {
            putBinder(HandoffContract.KEY_BINDER, binder)
            putInt(HandoffContract.KEY_UID, claims.reportedUid)
            putInt(HandoffContract.KEY_PID, claims.reportedPid)
            claims.nonce?.let { putString(HandoffContract.KEY_NONCE, it) }
            claims.apkPath?.let { putString(HandoffContract.KEY_APK, it) }
            claims.versionCode?.let { putLong(HandoffContract.KEY_VERSION_CODE, it) }
        }

    /** Reads untrusted `attach` extras; missing fields become `-1` / null. */
    public fun readOffer(extras: Bundle?): HelperOffer =
        HelperOffer(
            reportedUid = extras?.getInt(HandoffContract.KEY_UID, ABSENT) ?: ABSENT,
            reportedPid = extras?.getInt(HandoffContract.KEY_PID, ABSENT) ?: ABSENT,
            hasBinder = extras?.getBinder(HandoffContract.KEY_BINDER) != null,
            nonce = extras?.getString(HandoffContract.KEY_NONCE),
            apkPath = extras?.getString(HandoffContract.KEY_APK),
            versionCode =
                extras
                    ?.takeIf {
                        it.containsKey(
                            HandoffContract.KEY_VERSION_CODE,
                        )
                    }?.getLong(HandoffContract.KEY_VERSION_CODE),
        )

    /** The offered Binder, if any. */
    public fun binder(extras: Bundle?): IBinder? = extras?.getBinder(HandoffContract.KEY_BINDER)

    /** App reply: accepted, with the next resume nonce and the app's token. */
    public fun accepted(
        nextNonce: String,
        token: IBinder,
    ): Bundle =
        Bundle().apply {
            putBoolean(HandoffContract.KEY_ACCEPTED, true)
            putString(HandoffContract.KEY_NEXT_NONCE, nextNonce)
            putBinder(HandoffContract.KEY_TOKEN, token)
        }

    /** App reply: rejected for [reason]. */
    public fun rejected(reason: HandoffReject): Bundle =
        Bundle().apply {
            putBoolean(HandoffContract.KEY_ACCEPTED, false)
            putString(HandoffContract.KEY_REASON, reason.name)
        }

    /** What the helper reads from the app's reply. */
    public sealed interface Reply {
        /** The app took the Binder. */
        public data class Accepted(
            /** Nonce for the next handoff. */
            val nextNonce: String,
            /** App-process token to watch for death. */
            val token: IBinder,
        ) : Reply {
            /** Omits the nonce. */
            override fun toString(): String = "Accepted"
        }

        /** The app refused; [reason] is the [HandoffReject] name or a description of a malformed reply. */
        public data class Rejected(
            /** Why. */
            val reason: String,
        ) : Reply
    }

    /** Decodes the app's reply; anything other than a well-formed acceptance is a rejection. */
    public fun readReply(reply: Bundle?): Reply {
        val next = reply?.getString(HandoffContract.KEY_NEXT_NONCE)
        val token = reply?.getBinder(HandoffContract.KEY_TOKEN)
        return when {
            reply == null -> {
                Reply.Rejected("no reply")
            }

            !reply.getBoolean(HandoffContract.KEY_ACCEPTED, false) -> {
                Reply.Rejected(reply.getString(HandoffContract.KEY_REASON) ?: "rejected without reason")
            }

            next == null || !Nonces.isWellFormed(next) -> {
                Reply.Rejected("accepted without a well-formed next nonce")
            }

            token == null -> {
                Reply.Rejected("accepted without a token")
            }

            else -> {
                Reply.Accepted(next, token)
            }
        }
    }
}
