package dev.phoneagent.privileged.handoff

import dev.phoneagent.privileged.PrivilegedProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HandoffGateTest {
    private val shell = HandoffContract.SHELL_UID
    private val pid = 4242
    private val app = AppBuild(versionCode = 10001, apkPath = "/data/app/~~a==/dev.phoneagent-b==/base.apk")
    private val offer =
        HelperOffer(
            reportedUid = shell,
            reportedPid = pid,
            hasBinder = true,
            nonce = "0".repeat(Nonces.HEX_LENGTH),
            apkPath = app.apkPath,
            versionCode = app.versionCode,
        )

    @Test
    fun precheckAcceptsAShellHelperReportingItself() {
        assertNull(HandoffGate.precheck(shell, pid, offer))
    }

    @Test
    fun precheckRejectsCallersOtherThanShell() {
        assertEquals(HandoffReject.NOT_SHELL, HandoffGate.precheck(10_123, pid, offer.copy(reportedUid = 10_123)))
        assertEquals(HandoffReject.NOT_SHELL, HandoffGate.precheck(0, pid, offer.copy(reportedUid = 0)))
    }

    @Test
    fun precheckRejectsSelfReportsThatDifferFromTheBinderCaller() {
        assertEquals(HandoffReject.ID_MISMATCH, HandoffGate.precheck(shell, pid, offer.copy(reportedPid = pid + 1)))
        assertEquals(HandoffReject.ID_MISMATCH, HandoffGate.precheck(shell, pid, offer.copy(reportedUid = 10_123)))
        assertEquals(
            HandoffReject.ID_MISMATCH,
            HandoffGate.precheck(shell, pid, offer.copy(reportedUid = -1, reportedPid = -1)),
        )
    }

    @Test
    fun precheckRejectsOffersWithoutBinder() {
        assertEquals(HandoffReject.NO_BINDER, HandoffGate.precheck(shell, pid, offer.copy(hasBinder = false)))
    }

    @Test
    fun precheckReportsTheCallerFirst() {
        assertEquals(
            HandoffReject.NOT_SHELL,
            HandoffGate.precheck(10_123, pid, offer.copy(reportedPid = 1, hasBinder = false)),
        )
    }

    @Test
    fun identityMustNameTheCallingProcess() {
        assertNull(HandoffGate.checkIdentity(intArrayOf(shell, pid), shell, pid))
        assertNull("later versions may append fields", HandoffGate.checkIdentity(intArrayOf(shell, pid, 7), shell, pid))
        assertEquals(HandoffReject.BINDER_ELSEWHERE, HandoffGate.checkIdentity(intArrayOf(shell, pid + 1), shell, pid))
        assertEquals(HandoffReject.BINDER_ELSEWHERE, HandoffGate.checkIdentity(intArrayOf(10_123, pid), shell, pid))
        assertEquals(HandoffReject.BINDER_ELSEWHERE, HandoffGate.checkIdentity(intArrayOf(shell), shell, pid))
        assertEquals(HandoffReject.BINDER_ELSEWHERE, HandoffGate.checkIdentity(null, shell, pid))
    }

    @Test
    fun versionAcceptsTheExactBuild() {
        assertNull(HandoffGate.checkVersion(PrivilegedProtocol.VERSION, offer, app))
    }

    @Test
    fun versionRejectsAnotherProtocolFirst() {
        assertEquals(HandoffReject.STALE_PROTOCOL, HandoffGate.checkVersion(app.protocolVersion + 1, offer, app))
        assertEquals(HandoffReject.STALE_PROTOCOL, HandoffGate.checkVersion(app.protocolVersion - 1, offer, app))
        assertEquals(HandoffReject.STALE_PROTOCOL, HandoffGate.checkVersion(null, offer, app))
        assertEquals(
            HandoffReject.STALE_PROTOCOL,
            HandoffGate.checkVersion(app.protocolVersion + 1, offer.copy(versionCode = 1), app),
        )
    }

    @Test
    fun versionRejectsAnotherApk() {
        val v = app.protocolVersion
        assertEquals(
            HandoffReject.STALE_APK,
            HandoffGate.checkVersion(v, offer.copy(versionCode = app.versionCode - 1), app),
        )
        assertEquals(HandoffReject.STALE_APK, HandoffGate.checkVersion(v, offer.copy(versionCode = null), app))
        assertEquals(
            HandoffReject.STALE_APK,
            HandoffGate.checkVersion(v, offer.copy(apkPath = "/data/app/~~old/base.apk"), app),
        )
        assertEquals(HandoffReject.STALE_APK, HandoffGate.checkVersion(v, offer.copy(apkPath = null), app))
    }

    @Test
    fun offerToStringHidesTheNonce() {
        val nonce = "ab".repeat(Nonces.HEX_LENGTH / 2)
        assertFalse(offer.copy(nonce = nonce).toString().contains(nonce))
    }
}
