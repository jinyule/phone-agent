package dev.phoneagent.core.model

import dev.phoneagent.core.ids.BlobRef
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class AdapterPortsTest {
    @Test
    fun `a credential resolver refreshes after a rejection and has no secret for None`() =
        runTest {
            var issued = 0
            val resolver =
                CredentialResolver { credential, rejected ->
                    when (credential) {
                        Credential.None -> null
                        is Credential.ApiKey -> "sk-test-not-a-real-key"
                        is Credential.ChatGptPlan -> if (rejected) "token-${++issued}" else "token-$issued"
                    }
                }
            val plan = Credential.ChatGptPlan(SecretRef("chatgpt"))
            assertEquals(null, resolver.secret(Credential.None, rejected = false))
            assertEquals("sk-test-not-a-real-key", resolver.secret(Credential.ApiKey(SecretRef("k")), false))
            assertEquals("token-0", resolver.secret(plan, rejected = false))
            assertEquals("token-1", resolver.secret(plan, rejected = true))
        }

    @Test
    fun `a blob source returns the bytes or throws IOException`() =
        runTest {
            val present = BlobRef("a".repeat(64))
            val source =
                BlobSource { ref ->
                    if (ref == present) byteArrayOf(1, 2) else throw IOException("missing blob $ref")
                }
            assertArrayEquals(byteArrayOf(1, 2), source.bytes(present))
            assertThrows<IOException> { source.bytes(BlobRef("b".repeat(64))) }
        }
}
