package me.rerere.ai.provider

import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.EOFException
import java.io.IOException
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import javax.net.SocketFactory

/** A private transport per provider; never changes process-wide ProxySelector or Authenticator.
 * Synthetic DNS addresses retain the original hostname for connect(). Only the proxy resolves it.
 * TLS is still performed and verified by OkHttp against the original request URL.
 */
fun OkHttpClient.withProviderProxy(config: ProviderProxy): OkHttpClient {
    if (!config.enabled) return this
    config.validate()
    return newBuilder()
        .proxy(Proxy.NO_PROXY) // No system proxy or direct-route fallback inside this SOCKS tunnel.
        .socketFactory(Socks5SocketFactory(config))
        .dns(Dns { host -> listOf(InetAddress.getByAddress(host, byteArrayOf(192.toByte(), 0, 2, 1))) })
        .connectionPool(ConnectionPool())
        // Synthetic route IPs must not enable HTTP/2 cross-host connection coalescing.
        .hostnameVerifier { host, session -> hostnameVerifier.verify(host, session) }
        .build()
}

enum class Socks5Stage { CONNECT, HANDSHAKE, AUTH, TARGET }
class Socks5ProxyException(val stage: Socks5Stage, cause: Exception) : IOException("SOCKS5 $stage failed", cause)

private class Socks5SocketFactory(private val config: ProviderProxy) : SocketFactory() {
    override fun createSocket(): Socket = Socks5Socket(config)
    override fun createSocket(host: String, port: Int): Socket = createSocket().apply {
        connect(InetSocketAddress.createUnresolved(host, port))
    }
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = createSocket().apply {
        bind(InetSocketAddress(local, localPort))
        connect(InetSocketAddress.createUnresolved(host, port))
    }
    override fun createSocket(host: InetAddress, port: Int): Socket = createSocket(host.hostAddress!!, port)
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
        createSocket(host.hostAddress!!, port, local, localPort)
}

/** RFC 1928 + RFC 1929, including per-socket credentials (Android's global authenticator is not used). */
private class Socks5Socket(private val config: ProviderProxy) : Socket(Proxy.NO_PROXY) {
    override fun connect(endpoint: SocketAddress) = connect(endpoint, 10_000)

    override fun connect(endpoint: SocketAddress, timeout: Int) {
        require(endpoint is InetSocketAddress)
        val oldTimeout = soTimeout
        val budget = if (timeout > 0) timeout else 10_000
        val deadline = System.nanoTime() + budget * 1_000_000L
        var stage = Socks5Stage.CONNECT
        try {
            // Resolving a proxy hostname locally is necessary; destination names never use local DNS.
            super.connect(InetSocketAddress(config.host, config.port), budget)
            val input = getInputStream()
            val output = getOutputStream()
            fun readByte(): Int {
                val remaining = (deadline - System.nanoTime()) / 1_000_000
                if (remaining <= 0) throw SocketTimeoutException("SOCKS5 handshake timed out")
                soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                return input.read().takeIf { it >= 0 } ?: throw EOFException("SOCKS5 proxy closed the handshake")
            }
            stage = Socks5Stage.HANDSHAKE
            val auth = config.username.isNotEmpty()
            output.write(byteArrayOf(5, 1, if (auth) 2 else 0))
            output.flush()
            if (readByte() != 5) throw IOException("Invalid SOCKS5 greeting")
            val method = readByte()
            stage = Socks5Stage.AUTH
            if (method != if (auth) 2 else 0) throw IOException("SOCKS5 authentication method rejected")
            if (auth) {
                val user = config.username.toByteArray(Charsets.UTF_8)
                val pass = config.password.toByteArray(Charsets.UTF_8)
                output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                output.flush()
                if (readByte() != 1 || readByte() != 0) throw IOException("SOCKS5 authentication failed")
            }
            stage = Socks5Stage.TARGET
            val host = endpoint.hostString.removeSurrounding("[", "]")
            val address = when {
                ':' in host -> byteArrayOf(4) + InetAddress.getByName(host).address
                else -> {
                    val domain = IDN.toASCII(host).toByteArray(Charsets.US_ASCII)
                    if (domain.size !in 1..255) throw IOException("Invalid SOCKS5 destination hostname")
                    byteArrayOf(3, domain.size.toByte()) + domain
                }
            }
            output.write(byteArrayOf(5, 1, 0) + address + byteArrayOf((endpoint.port ushr 8).toByte(), endpoint.port.toByte()))
            output.flush()
            if (readByte() != 5) throw IOException("Invalid SOCKS5 reply")
            val status = readByte()
            if (status != 0) throw IOException("SOCKS5 target connection rejected (code $status)")
            if (readByte() != 0) throw IOException("Invalid SOCKS5 reserved byte")
            val length = when (readByte()) {
                1 -> 4
                3 -> readByte()
                4 -> 16
                else -> throw IOException("Invalid SOCKS5 address type")
            }
            repeat(length + 2) { readByte() }
            soTimeout = oldTimeout
        } catch (e: Exception) {
            runCatching { close() }
            throw Socks5ProxyException(stage, e)
        }
    }
}
