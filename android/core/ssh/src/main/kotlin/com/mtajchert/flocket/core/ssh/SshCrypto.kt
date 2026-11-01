package com.mtajchert.flocket.core.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

internal object SshCrypto {
    /**
     * Makes the bundled BouncyCastle the "BC" provider. On Android the platform ships a stripped
     * "BC" that lacks Ed25519/X25519, so it is replaced; on the JVM it is simply added.
     */
    @Synchronized
    fun ensureProvider() {
        val existing = Security.getProvider("BC")
        if (existing is BouncyCastleProvider) return
        if (existing != null) Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
