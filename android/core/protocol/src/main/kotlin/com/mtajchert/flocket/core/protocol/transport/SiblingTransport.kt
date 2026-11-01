package com.mtajchert.flocket.core.protocol.transport

/** A transport that can open another `hc connect` stream on the same underlying link (SSH session). */
interface SiblingTransportSource {
    suspend fun openSibling(): ByteTransport
}
