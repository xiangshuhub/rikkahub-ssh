package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

class ProviderProxyTest {
    private fun base() = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .dns(Dns { error("Destination must not use local DNS: $it") }).build()

    @Test fun `disabled proxy preserves existing default network and old settings deserialize`() {
        val client = base()
        assertSame(client, client.withProviderProxy(ProviderProxy()))
        val setting = Json.decodeFromString<ProviderSetting.OpenAI>("""{"name":"old provider"}""")
        assertEquals(ProviderProxy(), setting.proxy)
        assertEquals("127.0.0.1", setting.proxy.host)
        assertEquals(10808, setting.proxy.port)
    }

    @Test fun `proxy validation and debug strings do not disclose credentials`() {
        val config = ProviderProxy(enabled = true, username = "private-user", password = "private-password")
        config.validate()
        assertFalse(config.toString().contains("private"))
        assertFalse(ProviderSetting.OpenAI(proxy = config).toString().contains("private-password"))
        for (invalid in listOf(config.copy(port = 0), config.copy(host = "socks5://localhost"),
            config.copy(host = "localhost:10808"), config.copy(password = ""), config.copy(username = "x".repeat(256)))) {
            assertThrows(IllegalArgumentException::class.java) { invalid.validate() }
        }
    }

    @Test fun `hostname is sent to socks server and HTTP streaming works without local DNS`() {
        FakeSocks().use { proxy ->
            val client = base().withProviderProxy(proxy.config())
            client.newCall(Request.Builder().url("http://unresolvable.invalid/events").build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("data: hello", it.body!!.source().readUtf8Line())
                assertEquals("", it.body!!.source().readUtf8Line())
            }
            proxy.await()
            assertEquals("unresolvable.invalid", proxy.target)
            assertEquals(80, proxy.targetPort)
            assertNull(proxy.username)
        }
    }

    @Test fun `credentials are per socket and never added to target headers`() {
        FakeSocks(auth = true).use { first ->
            FakeSocks(auth = true).use { second ->
                for ((proxy, user) in listOf(first to "alice", second to "bob")) {
                    val client = base().withProviderProxy(proxy.config().copy(username = user, password = "$user-password"))
                    client.newCall(Request.Builder().url("http://model.invalid/models").build()).execute().close()
                    proxy.await()
                    assertEquals(user, proxy.username)
                    assertEquals("$user-password", proxy.password)
                    assertFalse(proxy.httpHeaders.contains("password"))
                    assertFalse(proxy.httpHeaders.contains("Proxy-Authorization", ignoreCase = true))
                }
            }
        }
    }

    @Test fun `failed socks authentication does not reach target`() {
        FakeSocks(auth = true, rejectAuth = true).use { proxy ->
            val client = base().withProviderProxy(proxy.config().copy(username = "user", password = "wrong"))
            val error = assertThrows(Socks5ProxyException::class.java) {
                client.newCall(Request.Builder().url("http://model.invalid/").build()).execute().close()
            }
            assertEquals(Socks5Stage.AUTH, error.stage)
            proxy.await()
            assertNull(proxy.target)
        }
    }

    @Test fun `target rejection never falls back to direct connection`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { direct ->
            direct.soTimeout = 200
            FakeSocks(rejectTarget = true).use { proxy ->
                val client = base().withProviderProxy(proxy.config())
                val error = assertThrows(Socks5ProxyException::class.java) {
                    client.newCall(Request.Builder().url("http://127.0.0.1:${direct.localPort}/").build()).execute().close()
                }
                assertEquals(Socks5Stage.TARGET, error.stage)
                proxy.await()
                assertThrows(SocketTimeoutException::class.java) { direct.accept().close() }
            }
        }
    }

    @Test fun `TLS verifies destination certificate over the socks tunnel`() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("secure.invalid").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        val base = base().newBuilder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        FakeSocks(tls = serverTls).use { proxy ->
            base.withProviderProxy(proxy.config()).newCall(Request.Builder().url("https://secure.invalid/").build())
                .execute().use { assertEquals(200, it.code) }
            proxy.await()
            assertEquals("secure.invalid", proxy.target)
        }
        FakeSocks(tls = serverTls).use { proxy ->
            assertThrows(SSLPeerUnverifiedException::class.java) {
                base.withProviderProxy(proxy.config()).newCall(Request.Builder().url("https://wrong.invalid/").build())
                    .execute().close()
            }
        }
    }

    @Test fun `manager isolates suppliers and rebuilds transport when settings change`() {
        val manager = ProviderManager(base(), null, AccountCredentialProvider { _, _ -> error("No account request expected") })
        val first = ProviderSetting.OpenAI(proxy = ProviderProxy(enabled = true))
        val instance = manager.getProviderByType(first)
        assertSame(instance, manager.getProviderByType(first))
        assertNotSame(instance, manager.getProviderByType(first.copy(id = kotlin.uuid.Uuid.random())))
        assertNotSame(instance, manager.getProviderByType(first.copy(proxy = first.proxy.copy(port = 10809))))
        assertSame(manager.getProvider("openai"), manager.getProviderByType(first.copy(proxy = ProviderProxy())))
        assertThrows(IllegalArgumentException::class.java) {
            manager.getProviderByType(first.copy(proxy = first.proxy.copy(host = "")))
        }
    }

    @Test fun `provider serialization and copy retain independent route`() {
        val proxy = ProviderProxy(enabled = true, port = 12345, username = "user", password = "pass")
        for (provider in listOf(ProviderSetting.OpenAI(), ProviderSetting.Claude(), ProviderSetting.Google(), ProviderSetting.ChatGPT(), ProviderSetting.Grok())) {
            val setting = provider.withProxy(proxy)
            val restored = Json.decodeFromString<ProviderSetting>(Json.encodeToString<ProviderSetting>(setting))
            assertEquals(proxy, restored.proxy)
            assertEquals(proxy, restored.copyProvider(name = "renamed").proxy)
            assertFalse(provider.proxy.enabled)
        }
    }

    private class FakeSocks(
        private val auth: Boolean = false,
        private val rejectAuth: Boolean = false,
        private val rejectTarget: Boolean = false,
        private val tls: HandshakeCertificates? = null,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile var target: String? = null
        @Volatile var targetPort = 0
        @Volatile var username: String? = null
        @Volatile var password: String? = null
        @Volatile var httpHeaders = ""
        private val task = executor.submit {
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                fun byte(): Int = input.read().also { if (it < 0) throw IOException("Unexpected EOF") }
                fun text(size: Int) = ByteArray(size) { byte().toByte() }.toString(Charsets.UTF_8)
                assertEquals(5, byte())
                assertEquals(1, byte())
                assertEquals(if (auth) 2 else 0, byte())
                output.write(byteArrayOf(5, if (auth) 2 else 0)); output.flush()
                if (auth) {
                    assertEquals(1, byte())
                    username = text(byte())
                    password = text(byte())
                    output.write(byteArrayOf(1, if (rejectAuth) 1 else 0)); output.flush()
                    if (rejectAuth) return@submit
                }
                assertEquals(5, byte()); assertEquals(1, byte()); assertEquals(0, byte())
                assertEquals(3, byte())
                target = text(byte())
                targetPort = (byte() shl 8) or byte()
                output.write(byteArrayOf(5, if (rejectTarget) 5 else 0, 0, 1, 127, 0, 0, 1, 0, 0)); output.flush()
                if (rejectTarget) return@submit
                val transport = tls?.sslSocketFactory()?.createSocket(socket, target, targetPort, true)?.let {
                    (it as SSLSocket).apply { useClientMode = false; startHandshake() }
                } ?: socket
                val reader = transport.getInputStream().bufferedReader()
                httpHeaders = buildString {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        appendLine(line)
                    }
                }
                val body = "data: hello\n\n"
                transport.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n" + body).toByteArray())
                transport.getOutputStream().flush()
                if (transport !== socket) transport.close()
            }
        }
        fun config() = ProviderProxy(enabled = true, port = server.localPort)
        fun await() { task.get(5, TimeUnit.SECONDS) }
        override fun close() { server.close(); task.cancel(true); executor.shutdownNow() }
    }
}
