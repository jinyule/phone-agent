package dev.phoneagent.core.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException

/** How connect and command failures are classified, from the exceptions alone. */
class AdbFailureClassTest {
    @Test
    fun `failures are classified by step, with a refusal after our certificate as UNAUTHORIZED`() {
        fun classify(
            failure: IOException,
            step: ConnectFailure,
        ) = AdbHandshake.classify(failure, step).reason
        val reset = SocketException("Broken pipe")
        assertEquals(ConnectFailure.UNAUTHORIZED, classify(TlsHandshakeException(true, reset), ConnectFailure.TLS))
        assertEquals(
            ConnectFailure.UNAUTHORIZED,
            classify(TlsHandshakeException(true, SSLException("alert")), ConnectFailure.TLS),
        )
        assertEquals(
            ConnectFailure.TLS,
            classify(TlsHandshakeException(false, SSLException("alert")), ConnectFailure.TLS),
        )
        assertEquals(
            ConnectFailure.UNAUTHORIZED,
            classify(SSLException("bad_certificate"), ConnectFailure.UNAUTHORIZED),
        )
        assertEquals(ConnectFailure.UNAUTHORIZED, classify(TruncatedFrameException("eof"), ConnectFailure.UNAUTHORIZED))
        assertEquals(ConnectFailure.UNAUTHORIZED, classify(reset, ConnectFailure.UNAUTHORIZED))
        assertEquals(ConnectFailure.PROTOCOL, classify(AdbProtocolException("bad magic"), ConnectFailure.UNAUTHORIZED))
        assertEquals(ConnectFailure.TLS, classify(SSLException("x"), ConnectFailure.PROTOCOL))
        assertEquals(ConnectFailure.PROTOCOL, classify(TruncatedFrameException("eof"), ConnectFailure.PROTOCOL))
        assertEquals(ConnectFailure.UNREACHABLE, classify(reset, ConnectFailure.UNREACHABLE))
        val classified = ConnectError(ConnectFailure.TIMEOUT, "kept")
        assertSame(classified, AdbHandshake.classify(classified, ConnectFailure.TLS))
    }

    @Test
    fun `command failures are classified by exception type`() {
        fun classify(failure: IOException) = CommandExchange.classify(failure).reason
        assertEquals(CommandFailure.OUTPUT_TOO_LARGE, classify(OutputTooLargeException(10)))
        assertEquals(CommandFailure.CLOSED, classify(TruncatedFrameException("eof")))
        assertEquals(CommandFailure.PROTOCOL, classify(AdbProtocolException("bad magic")))
        assertEquals(CommandFailure.CLOSED, classify(SocketException("Socket closed")))
    }

    @Test
    fun `what our TLS stack names by type is TLS, even where a refusal could be`() {
        fun classify(
            failure: IOException,
            step: ConnectFailure,
        ) = AdbHandshake.classify(failure, step).reason
        val badMac = SSLException("(bad_record_mac) Insufficient buffer", BadPaddingException("tag"))
        val nested = SSLException("record", IOException("wrapped", AEADBadTagException("tag")))
        val decode = SSLProtocolException("(unexpected_message) Unexpected handshake message")
        val wrapped = SSLException("(decode_error) Invalid record", SSLProtocolException("short record"))
        for (failure in listOf(badMac, nested, decode, wrapped)) {
            assertEquals(ConnectFailure.TLS, classify(failure, ConnectFailure.UNAUTHORIZED), "$failure")
            assertEquals(ConnectFailure.TLS, classify(TlsHandshakeException(true, failure), ConnectFailure.TLS))
        }
        // An alert adbd sends carries no type or cause: it may be a refusal, so it stays one.
        assertEquals(
            ConnectFailure.UNAUTHORIZED,
            classify(SSLException("certificate_required"), ConnectFailure.UNAUTHORIZED),
        )
        assertEquals(
            ConnectFailure.UNAUTHORIZED,
            classify(SSLException("alert", IOException("no crypto")), ConnectFailure.UNAUTHORIZED),
        )
    }

    @Test
    fun `an alert adbd sends stays a possible refusal when Conscrypt types it as a protocol error`() {
        fun classify(
            failure: IOException,
            step: ConnectFailure,
        ) = AdbHandshake.classify(failure, step).reason
        // Verbatim from an unpaired key's first read on Android 16 (emulator, SDK 36).
        val refusedOnDevice =
            SSLProtocolException(
                "Read error: ssl=0xb400007650c27b18: Failure in SSL library, usually a protocol error\n" +
                    "error:10000416:SSL routines:OPENSSL_internal:SSLV3_ALERT_CERTIFICATE_UNKNOWN " +
                    "(external/boringssl/src/ssl/tls_record.cc:484 0xb400007670cb6f40:0x00000003)",
            )
        val tls13Alert =
            SSLProtocolException("error:1000045c:SSL routines:OPENSSL_internal:TLSV1_ALERT_CERTIFICATE_REQUIRED")
        for (alert in listOf(refusedOnDevice, tls13Alert)) {
            assertEquals(ConnectFailure.UNAUTHORIZED, classify(alert, ConnectFailure.UNAUTHORIZED), "$alert")
            assertEquals(ConnectFailure.UNAUTHORIZED, classify(TlsHandshakeException(true, alert), ConnectFailure.TLS))
            // Before adbd asked for our certificate, an alert is no answer to our key.
            assertEquals(ConnectFailure.TLS, classify(TlsHandshakeException(false, alert), ConnectFailure.TLS))
        }
        // What BoringSSL finds itself has a name of its own and stays TLS, also when it is about an
        // alert or its code is above 1000; so does an alert's name or reason only inside another word.
        val ownFindings =
            listOf(
                "error:1000008b:SSL routines:OPENSSL_internal:DECRYPTION_FAILED_OR_BAD_RECORD_MAC",
                "error:10000066:SSL routines:OPENSSL_internal:BAD_ALERT",
                "error:100000e3:SSL routines:OPENSSL_internal:UNKNOWN_ALERT_TYPE",
                "error:10000462:SSL routines:OPENSSL_internal:PAKE_AND_KEY_SHARE_NOT_ALLOWED",
                "error:10000416:SSL routines:OPENSSL_internal:XSSLV3_ALERT_CERTIFICATE_UNKNOWN",
                "error:1000045c:SSL routines:OPENSSL_internal:_TLSV1_ALERT_CERTIFICATE_REQUIRED",
                "error:10000455:SSL routines:OPENSSL_internal:Xreason(1109)",
            ).map(::conscryptError)
        for (ownFinding in ownFindings) {
            assertEquals(ConnectFailure.TLS, classify(ownFinding, ConnectFailure.UNAUTHORIZED), "$ownFinding")
            assertEquals(ConnectFailure.TLS, classify(TlsHandshakeException(true, ownFinding), ConnectFailure.TLS))
        }
        assertEquals(ConnectFailure.TLS, classify(SSLProtocolException(null), ConnectFailure.UNAUTHORIZED))
    }

    @Test
    fun `an alert the peer sends is a possible refusal on the JDK and on Conscrypt alike, named or not`() {
        // The JDK's shapes (sun.security.ssl.Alert): a received unexpected_message, and an alert
        // number it does not know, come as a protocol error without a cause.
        val jdk =
            listOf(
                SSLProtocolException("(unexpected_message) Received fatal alert: unexpected_message"),
                SSLHandshakeException("(missing_extension) Received fatal alert: missing_extension"),
                SSLProtocolException("(unexpected_message) Unknown alert description (200)"),
            )
        // BoringSSL writes reason(N) for a code its table has no name for, as for these alerts;
        // Android 11's table names 1110-1116 without ALERT_.
        val conscrypt =
            listOf(
                "error:100003f2:SSL routines:OPENSSL_internal:SSLV3_ALERT_UNEXPECTED_MESSAGE",
                "error:1000045c:SSL routines:OPENSSL_internal:TLSV1_CERTIFICATE_REQUIRED",
                "error:10000455:SSL routines:OPENSSL_internal:reason(1109)",
                "error:100004b0:SSL routines:OPENSSL_internal:reason(1200)",
            ).map(::conscryptError)
        val alerts = jdk + conscrypt

        fun classifiedAll(
            step: ConnectFailure,
            wrap: (IOException) -> IOException,
        ) = alerts.associateWith { AdbHandshake.classify(wrap(it), step).reason }
        val refusals = alerts.associateWith { ConnectFailure.UNAUTHORIZED }
        assertEquals(refusals, classifiedAll(ConnectFailure.UNAUTHORIZED) { it })
        assertEquals(refusals, classifiedAll(ConnectFailure.TLS) { TlsHandshakeException(true, it) })
        // Before adbd asked for our certificate, an alert is no answer to our key.
        val early = alerts.associateWith { ConnectFailure.TLS }
        assertEquals(early, classifiedAll(ConnectFailure.TLS) { TlsHandshakeException(false, it) })
    }

    @Test
    fun `a socket timeout is TIMEOUT in every step`() {
        val timeout = SocketTimeoutException("Read timed out")
        for (step in ConnectFailure.entries) {
            assertEquals(ConnectFailure.TIMEOUT, AdbHandshake.classify(timeout, step).reason, "$step")
        }
        for (requested in listOf(true, false)) {
            val inHandshake = TlsHandshakeException(requested, timeout)
            assertEquals(ConnectFailure.TIMEOUT, AdbHandshake.classify(inHandshake, ConnectFailure.TLS).reason)
        }
        assertEquals(CommandFailure.TIMEOUT, CommandExchange.classify(timeout).reason)
    }
}

/** An error as Conscrypt raises it for one entry of BoringSSL's error queue (jniutil.cc). */
private fun conscryptError(entry: String) =
    SSLProtocolException("Read error: ssl=0x1: Failure in SSL library, usually a protocol error\n$entry")
