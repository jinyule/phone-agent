package dev.phoneagent.core.adb

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** The whole setup flow against the fakes: create an identity, pair, connect, run a command. */
class AdbEndToEndTest {
    @Test
    fun `pair, connect and run with a fresh identity`() {
        val store =
            object : AdbKeyStore {
                var bytes: ByteArray? = null

                override fun read(): ByteArray? = bytes

                override fun write(pkcs8: ByteArray) {
                    bytes = pkcs8
                }
            }
        var identity: AdbIdentity? = null
        runTest {
            identity =
                AdbIdentity.loadOrCreate(store, "phoneagent@e2e", StandardTestDispatcher(testScheduler)).identity
        }
        val client = AdbClient(checkNotNull(identity), TestTls.exporter)
        val pairedKeys = CopyOnWriteArrayList<ByteArray>()
        realTime {
            FakePairingServer("135790", guid = "adb-E2E-guid").use { pairing ->
                assertEquals(
                    PairingResult.Paired(PairedDevice("adb-E2E-guid")),
                    client.pair(pairing.endpoint, PairingCode("135790")),
                )
                pairedKeys += TestTls.decodeAdbKey(pairing.receivedKey.get(10, TimeUnit.SECONDS)).encoded
            }
            FakeAdbd({ pairedKeys }, reply = { command -> FakeAdbd.Reply.Exit(0, "ran $command") }).use { adbd ->
                val result = client.connect(adbd.endpoint)
                assertTrue(result is ConnectResult.Connected) { "got $result" }
                (result as ConnectResult.Connected).session.use { session ->
                    val command = AdbCommand("echo " + AdbCommand.quote("it's ok"))
                    assertEquals(CommandResult.Completed(0, "ran echo 'it'\\''s ok'", ""), session.run(command))
                }
            }
            // Another identity was never paired with this device.
            FakeAdbd({ pairedKeys }).use { adbd ->
                val stranger = AdbClient(TestTls.stranger, TestTls.exporter).connect(adbd.endpoint)
                assertEquals(ConnectFailure.UNAUTHORIZED, (stranger as ConnectResult.Failed).reason)
            }
        }
    }
}
