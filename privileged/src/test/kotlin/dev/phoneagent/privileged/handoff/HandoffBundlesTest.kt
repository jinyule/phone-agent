package dev.phoneagent.privileged.handoff

import android.os.Binder
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HandoffBundlesTest {
    private val nonce = "1f".repeat(Nonces.HEX_LENGTH / 2)

    @Test
    fun offerRoundTrips() {
        val binder = Binder()
        val claims = HelperOffer(2000, 4242, true, nonce, "/data/app/x/base.apk", 10001)
        val extras = HandoffBundles.offer(binder, claims)
        assertEquals(claims, HandoffBundles.readOffer(extras))
        assertSame(binder, HandoffBundles.binder(extras))
    }

    @Test
    fun absentClaimsStayAbsent() {
        val claims = HelperOffer(2000, 4242, true, null, null, null)
        assertEquals(claims, HandoffBundles.readOffer(HandoffBundles.offer(Binder(), claims)))
    }

    @Test
    fun missingFieldsReadAsAbsent() {
        assertEquals(HelperOffer(-1, -1, false, null, null, null), HandoffBundles.readOffer(null))
        assertEquals(HelperOffer(-1, -1, false, null, null, null), HandoffBundles.readOffer(Bundle()))
        assertNull(HandoffBundles.binder(null))
    }

    @Test
    fun acceptedReplyRoundTrips() {
        val token = Binder()
        val reply = HandoffBundles.readReply(HandoffBundles.accepted(nonce, token))
        assertEquals(HandoffBundles.Reply.Accepted(nonce, token), reply)
        assertFalse("toString hides the nonce", reply.toString().contains(nonce))
    }

    @Test
    fun rejectedReplyCarriesTheReason() {
        assertEquals(
            HandoffBundles.Reply.Rejected("STALE_APK"),
            HandoffBundles.readReply(HandoffBundles.rejected(HandoffReject.STALE_APK)),
        )
    }

    @Test
    fun malformedRepliesAreRejections() {
        assertTrue(HandoffBundles.readReply(null) is HandoffBundles.Reply.Rejected)
        assertTrue(HandoffBundles.readReply(Bundle()) is HandoffBundles.Reply.Rejected)
        val noNonce =
            Bundle().apply {
                putBoolean(HandoffContract.KEY_ACCEPTED, true)
                putBinder(HandoffContract.KEY_TOKEN, Binder())
            }
        assertTrue(HandoffBundles.readReply(noNonce) is HandoffBundles.Reply.Rejected)
        val badNonce = Bundle(noNonce).apply { putString(HandoffContract.KEY_NEXT_NONCE, "not-a-nonce") }
        assertTrue(HandoffBundles.readReply(badNonce) is HandoffBundles.Reply.Rejected)
        val noToken =
            Bundle().apply {
                putBoolean(HandoffContract.KEY_ACCEPTED, true)
                putString(HandoffContract.KEY_NEXT_NONCE, nonce)
            }
        assertTrue(HandoffBundles.readReply(noToken) is HandoffBundles.Reply.Rejected)
    }
}
