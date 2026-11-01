package dev.phoneagent.privileged.handoff

import java.security.MessageDigest

/** Fingerprint of an app's signing certificates, as the helper pins it. */
public object CertPin {
    /**
     * SHA-256 of each DER-encoded certificate in lowercase hex, sorted and joined with `,`
     * (order-independent for multi-signer APKs). Null when [certificates] is empty.
     */
    public fun of(certificates: List<ByteArray>): String? {
        if (certificates.isEmpty()) return null
        return certificates
            .map { MessageDigest.getInstance("SHA-256").digest(it).toHexString() }
            .sorted()
            .joinToString(",")
    }
}
