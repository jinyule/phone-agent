package dev.phoneagent.privileged.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CertPinTest {
    private val a = "abc".toByteArray()
    private val b = "other".toByteArray()

    @Test
    fun noCertificatesMeansNoPin() {
        assertNull(CertPin.of(emptyList()))
    }

    @Test
    fun pinIsTheSha256OfTheCertificate() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CertPin.of(listOf(a)))
    }

    @Test
    fun multipleSignersAreOrderIndependent() {
        assertEquals(CertPin.of(listOf(a, b)), CertPin.of(listOf(b, a)))
        assertNotEquals(CertPin.of(listOf(a)), CertPin.of(listOf(a, b)))
    }
}
