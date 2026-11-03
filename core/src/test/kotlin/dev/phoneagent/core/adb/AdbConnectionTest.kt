package dev.phoneagent.core.adb

import dev.phoneagent.core.adb.FakeAdbd.Behavior
import dev.phoneagent.core.adb.FakeAdbd.Reply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

class AdbConnectionTest {
    private val identity = TestTls.client
    private val paired = { listOf(identity.rsaPublicKey.encoded) }
    private val id = AdbCommand("id")

    @Test
    fun `connects over TLS and runs a command with the shell protocol`() =
        realTime {
            FakeAdbd(paired, reply = { Reply.Exit(0, "uid=2000(shell) gid=2000(shell)\n", "warn\n") }).use { adbd ->
                withSession(adbd) { session ->
                    assertTrue(session.banner.startsWith("device::ro.product.name=fake;"))
                    assertEquals(setOf("shell_v2", "cmd", "stat_v2"), session.features)
                    assertEquals(
                        CommandResult.Completed(0, "uid=2000(shell) gid=2000(shell)\n", "warn\n"),
                        session.run(id),
                    )
                    assertTrue(session.isOpen)
                    assertEquals("AdbSession(open=true, features=[shell_v2, cmd, stat_v2])", session.toString())
                }
                assertEquals(listOf("shell,v2,raw:id"), adbd.opened)
            }
        }

    @Test
    fun `stdin reaches the command in pieces of the device's max payload and output may come in tiny pieces`() =
        realTime {
            val reply = Reply.Exit(7, "got:", "", echo = true, chunk = 3)
            FakeAdbd(paired, maxPayload = 17, reply = { reply }).use { adbd ->
                withSession(adbd) { session ->
                    val input = "the quick brown fox jumps over the lazy dog".toByteArray()
                    val result = session.run(AdbCommand("cat"), input)
                    assertEquals(
                        CommandResult.Completed(7, "got:the quick brown fox jumps over the lazy dog", ""),
                        result,
                    )
                    assertEquals(CommandResult.Completed(7, "got:", ""), session.run(id))
                }
                assertEquals("the quick brown fox jumps over the lazy dog", adbd.stdins.first().decodeToString())
                assertEquals(emptyList<Int>(), adbd.oversizedWrites)
            }
        }

    @Test
    fun `without the shell protocol it uses exec and has no exit status`() =
        realTime {
            FakeAdbd(paired, features = "cmd", reply = { Reply.Exit(3, "out ", "err") }).use { adbd ->
                withSession(adbd) { session ->
                    assertEquals(CommandResult.Completed(null, "out err", ""), session.run(id, "ignored".toByteArray()))
                    assertEquals(CommandResult.Completed(null, "out err", ""), session.run(id))
                }
                assertEquals(listOf("exec:id", "exec:id"), adbd.opened)
            }
        }

    @Test
    fun `exec with output cut short is still complete, as adb sees it`() =
        realTime {
            FakeAdbd(paired, features = "", reply = { Reply.CloseWithoutExit }).use { adbd ->
                withSession(adbd) { session ->
                    val result = session.run(id) as CommandResult.Completed
                    assertNull(result.exitCode)
                    assertTrue(result.stdout.endsWith("partial"))
                }
            }
        }

    @Test
    fun `packets of other streams are skipped and sessions run commands one after another`() =
        realTime {
            var count = 0
            FakeAdbd(paired, reply = { Reply.Exit(0, "${++count}", strayFirst = true) }).use { adbd ->
                withSession(adbd) { session ->
                    assertEquals(CommandResult.Completed(0, "1", ""), session.run(id))
                    assertEquals(CommandResult.Completed(0, "2", ""), session.run(id))
                }
            }
        }

    @Test
    fun `a refused service leaves the session usable`() =
        realTime {
            var first = true
            val reply = { _: String -> if (first) Reply.Refuse.also { first = false } else Reply.Exit(0, "ok") }
            FakeAdbd(paired, reply = reply).use { adbd ->
                withSession(adbd) { session ->
                    assertFailure(CommandFailure.REFUSED, session.run(id))
                    assertTrue(session.isOpen)
                    assertEquals(CommandResult.Completed(0, "ok", ""), session.run(id))
                }
            }
        }

    @ParameterizedTest
    @CsvSource(
        "CloseWithoutExit, CLOSED",
        "DropConnection, CLOSED",
        "Garbage, PROTOCOL",
        "OkayWithoutId, PROTOCOL",
        "WriteBeforeOkay, PROTOCOL",
        "BadExitLength, PROTOCOL",
        "DataAfterExit, PROTOCOL",
        "Flood, OUTPUT_TOO_LARGE",
        "CloseWithWrongId, PROTOCOL",
        "AckWithWrongId, PROTOCOL",
        "WriteWithWrongId, PROTOCOL",
        "CnxnInStream, PROTOCOL",
        "UnknownCommand, PROTOCOL",
        "UnsolicitedOkay, PROTOCOL",
    )
    fun `a broken stream fails with its class and closes the session`(
        name: String,
        expected: CommandFailure,
    ) = realTime {
        val reply = replies.getValue(name)
        FakeAdbd(paired, reply = { reply }).use { adbd ->
            val config = AdbConfig(maxOutputBytes = 64 * 1024)
            withSession(adbd, config) { session ->
                assertFailure(expected, session.run(id))
                assertFalse(session.isOpen)
                assertFailure(CommandFailure.CLOSED, session.run(id))
            }
        }
    }

    @Test
    fun `checksums are verified only with an old protocol version`() =
        realTime {
            FakeAdbd(paired, Behavior.OLD_VERSION, reply = { Reply.BadChecksum }).use { adbd ->
                withSession(adbd) { session -> assertFailure(CommandFailure.PROTOCOL, session.run(id)) }
            }
            FakeAdbd(paired, reply = { Reply.BadChecksum }).use { adbd ->
                withSession(adbd) { session -> assertEquals(CommandResult.Completed(0, "", ""), session.run(id)) }
            }
        }

    @Test
    fun `a command that does not finish times out in virtual time and closes the session`() {
        FakeAdbd(paired, reply = { Reply.Hang }).use { adbd ->
            // Connecting needs real time: under runTest the connect timeout would fire at once.
            val session = inRealTime { connect(adbd, AdbConfig(commandTimeoutMs = 2_500)) }
            runTest {
                val result = async { session.run(id) }
                holdTimeUntil(adbd.gotOpen)
                assertFailure(CommandFailure.TIMEOUT, result.await())
                assertEquals(2_500, virtualTime)
                assertFalse(session.isOpen)
                adbd.clientClosed.get(10, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `cancelling a command closes the session and propagates`() =
        realTime {
            FakeAdbd(paired, reply = { Reply.Hang }).use { adbd ->
                val session = connect(adbd)
                val result = async { session.run(id) }
                adbd.gotOpen.awaitBarrier()
                result.cancel()
                assertThrows<CancellationException> { result.await() }
                assertFalse(session.isOpen)
                adbd.clientClosed.awaitBarrier()
            }
        }

    @Test
    fun `closing the session during a command ends it as CLOSED`() =
        realTime {
            FakeAdbd(paired, reply = { Reply.Hang }).use { adbd ->
                val session = connect(adbd)
                val result = async { session.run(id) }
                adbd.gotOpen.awaitBarrier()
                session.close()
                assertFailure(CommandFailure.CLOSED, result.await())
                session.close()
                assertFalse(session.isOpen)
            }
        }

    /** adbd refuses inside the handshake; whether our own handshake has finished by then varies, the class does not. */
    @RepeatedTest(10)
    fun `a key the device does not know is UNAUTHORIZED`() =
        realTime {
            FakeAdbd({ emptyList() }).use { adbd ->
                assertConnectFailure(ConnectFailure.UNAUTHORIZED, client().connect(adbd.endpoint))
            }
        }

    @ParameterizedTest
    @CsvSource(
        "HANG_UP, PROTOCOL",
        "AUTH, PROTOCOL",
        "PLAIN_CNXN, PROTOCOL",
        "BAD_MAGIC, PROTOCOL",
        "OKAY_FIRST, PROTOCOL",
        "NOT_TLS_AFTER_STLS, TLS",
        "HANG_UP_AFTER_TLS, UNAUTHORIZED",
        "OKAY_INSTEAD_OF_CNXN, PROTOCOL",
        "ZERO_MAX_PAYLOAD, PROTOCOL",
        "ANCIENT_VERSION, PROTOCOL",
    )
    fun `each broken handshake has its failure class`(
        behavior: Behavior,
        expected: ConnectFailure,
    ) = realTime {
        FakeAdbd(paired, behavior).use { adbd ->
            assertConnectFailure(expected, client().connect(adbd.endpoint))
            adbd.close()
            assertNull(adbd.join(), "the fake itself worked")
        }
    }

    @Test
    fun `a closed port is UNREACHABLE`() =
        realTime {
            assertConnectFailure(ConnectFailure.UNREACHABLE, client().connect(AdbPairingTest.closedPort()))
        }

    @Test
    fun `a silent peer times out in virtual time, before and after TLS`() =
        runTest {
            for (behavior in listOf(Behavior.SILENT, Behavior.SILENT_AFTER_TLS)) {
                FakeAdbd(paired, behavior).use { adbd ->
                    val start = virtualTime
                    val result = async { client(AdbConfig(connectTimeoutMs = 3_000)).connect(adbd.endpoint) }
                    holdTimeUntil(if (behavior == Behavior.SILENT) adbd.gotCnxn else adbd.gotTls)
                    assertConnectFailure(ConnectFailure.TIMEOUT, result.await())
                    assertEquals(3_000, virtualTime - start, "$behavior")
                    adbd.clientClosed.get(10, TimeUnit.SECONDS)
                }
            }
        }

    @Test
    fun `cancelling a connect closes the socket and propagates`() =
        realTime {
            FakeAdbd(paired, Behavior.SILENT).use { adbd ->
                val result = async { client().connect(adbd.endpoint) }
                adbd.gotCnxn.awaitBarrier()
                result.cancel()
                assertThrows<CancellationException> { result.await() }
                adbd.clientClosed.awaitBarrier()
            }
        }

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
    fun `adbd's CNXN has no checksum from the skip-checksum version on, an older one's must match`() =
        realTime {
            val banner = "device::ro.product.name=raw;features=shell_v2"
            val sum = banner.toByteArray().sumOf { it.toInt() and 0xff }
            val cases =
                listOf(
                    Triple(0x01000001, 0, true),
                    Triple(0x01000000, sum, true),
                    Triple(0x01000000, sum + 1, false),
                )
            for ((version, dataCheck, accepted) in cases) {
                val bytes = handBuiltCnxn(version, dataCheck, banner)
                val behavior = if (version < Aosp.A_VERSION_SKIP_CHECKSUM) Behavior.OLD_VERSION else Behavior.NORMAL
                FakeAdbd(paired, behavior, cnxnBytes = bytes).use { adbd ->
                    val result = client().connect(adbd.endpoint)
                    if (accepted) {
                        assertTrue(result is ConnectResult.Connected) { "version $version: $result" }
                        (result as ConnectResult.Connected).session.close()
                    } else {
                        assertConnectFailure(ConnectFailure.PROTOCOL, result)
                    }
                }
            }
        }

    @Test
    fun `an OKAY with the wrong remote id does not let the next piece of stdin go`() =
        realTime {
            FakeAdbd(paired, maxPayload = 17, reply = { Reply.AckWithWrongId }).use { adbd ->
                withSession(adbd) { session ->
                    assertFailure(
                        CommandFailure.PROTOCOL,
                        session.run(AdbCommand("cat"), ByteArray(20) { 'x'.code.toByte() }),
                    )
                    assertFalse(session.isOpen)
                }
            }
        }

    @Test
    fun `an exec stream closed with the wrong remote id is not complete`() =
        realTime {
            FakeAdbd(paired, features = "", reply = { Reply.CloseWithWrongId }).use { adbd ->
                withSession(adbd) { session ->
                    assertFailure(CommandFailure.PROTOCOL, session.run(id))
                    assertFalse(session.isOpen)
                }
            }
        }

    @Test
    fun `a command longer than adbd's max payload is not sent and the session stays usable`() =
        realTime {
            FakeAdbd(paired, features = "", maxPayload = 8, reply = { Reply.Exit(0, "ok") }).use { adbd ->
                withSession(adbd) { session ->
                    assertFailure(CommandFailure.TOO_LONG, session.run(AdbCommand("echo a-longer-command")))
                    assertTrue(session.isOpen)
                    assertEquals(CommandResult.Completed(null, "ok", ""), session.run(id))
                }
                assertEquals(emptyList<Int>(), adbd.oversizedOpens)
                assertEquals(listOf("exec:id"), adbd.opened)
            }
        }

    @Test
    fun `adbd reads the features of our banner as shell_v2, without a NUL`() =
        realTime {
            FakeAdbd(paired).use { adbd ->
                withSession(adbd) { session -> assertTrue(session.isOpen) }
                assertEquals("shell_v2", adbd.hostFeatures.get(10, TimeUnit.SECONDS))
            }
        }

    @Test
    fun `features come from the banner's features entry`() {
        assertEquals(setOf("shell_v2", "cmd"), AdbFeatures.of("device::ro.product.name=x;features=shell_v2,,cmd"))
        assertEquals(emptySet<String>(), AdbFeatures.of("device::ro.product.name=x"))
        assertEquals(emptySet<String>(), AdbFeatures.of("features=shell_v2"))
        assertEquals(emptySet<String>(), AdbFeatures.of("device::features="))
    }

    private fun client(config: AdbConfig = AdbConfig()) = AdbClient(identity, TestTls.exporter, config)

    private suspend fun connect(
        adbd: FakeAdbd,
        config: AdbConfig = AdbConfig(),
    ): AdbSession {
        val result = client(config).connect(adbd.endpoint)
        assertTrue(result is ConnectResult.Connected) { "expected a session, got $result" }
        return (result as ConnectResult.Connected).session
    }

    private suspend fun CoroutineScope.withSession(
        adbd: FakeAdbd,
        config: AdbConfig = AdbConfig(),
        block: suspend CoroutineScope.(AdbSession) -> Unit,
    ) {
        connect(adbd, config).use { block(it) }
    }

    private fun assertFailure(
        expected: CommandFailure,
        result: CommandResult,
    ) {
        assertTrue(result is CommandResult.Failed && result.reason == expected) { "expected $expected, got $result" }
    }

    private fun assertConnectFailure(
        expected: ConnectFailure,
        result: ConnectResult,
    ) {
        assertTrue(result is ConnectResult.Failed && result.reason == expected) { "expected $expected, got $result" }
    }

    /** adbd's CNXN written by hand as AOSP lays it out (`amessage`, little-endian), not by any encoder. */
    private fun handBuiltCnxn(
        version: Int,
        dataCheck: Int,
        banner: String,
    ): ByteArray {
        fun le(value: Int) = ByteArray(4) { (value ushr (8 * it)).toByte() }
        val payload = banner.toByteArray()
        val cnxn = 0x4e584e43
        return le(cnxn) + le(version) + le(0x00100000) + le(payload.size) + le(dataCheck) + le(cnxn.inv()) + payload
    }

    private companion object {
        val replies =
            mapOf(
                "CloseWithoutExit" to Reply.CloseWithoutExit,
                "DropConnection" to Reply.DropConnection,
                "Garbage" to Reply.Garbage,
                "OkayWithoutId" to Reply.OkayWithoutId,
                "WriteBeforeOkay" to Reply.WriteBeforeOkay,
                "BadExitLength" to Reply.BadExitLength,
                "DataAfterExit" to Reply.DataAfterExit,
                "Flood" to Reply.Flood(64 * 1024 + 1),
                "CloseWithWrongId" to Reply.CloseWithWrongId,
                "AckWithWrongId" to Reply.AckWithWrongId,
                "WriteWithWrongId" to Reply.WriteWithWrongId,
                "CnxnInStream" to Reply.CnxnInStream,
                "UnknownCommand" to Reply.UnknownCommand,
                "UnsolicitedOkay" to Reply.UnsolicitedOkay,
            )
    }
}
