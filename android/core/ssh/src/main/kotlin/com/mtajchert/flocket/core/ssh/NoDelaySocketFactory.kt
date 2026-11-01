package com.mtajchert.flocket.core.ssh

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Plain sockets with TCP_NODELAY. The link carries many small writes that someone waits for:
 * keystrokes and CREDIT frames (the core sends nothing more on a stream until credit is back).
 * With Nagle on, a small write waits for the ACK of the previous one, which a peer may delay
 * by 40-200 ms, stalling the credit loop and adding keystroke latency.
 */
internal object NoDelaySocketFactory : SocketFactory() {
    private val default: SocketFactory = getDefault()

    private fun Socket.noDelay(): Socket = apply { tcpNoDelay = true }

    override fun createSocket(): Socket = Socket().noDelay()

    override fun createSocket(
        host: String?,
        port: Int,
    ): Socket = default.createSocket(host, port).noDelay()

    override fun createSocket(
        host: String?,
        port: Int,
        localHost: InetAddress?,
        localPort: Int,
    ): Socket = default.createSocket(host, port, localHost, localPort).noDelay()

    override fun createSocket(
        host: InetAddress?,
        port: Int,
    ): Socket = default.createSocket(host, port).noDelay()

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int,
    ): Socket = default.createSocket(address, port, localAddress, localPort).noDelay()
}
