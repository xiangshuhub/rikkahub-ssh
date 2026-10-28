package me.rerere.ai.provider

import kotlinx.serialization.Serializable

@Serializable
data class ProviderProxy(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 10808,
    val username: String = "",
    val password: String = "",
) {
    fun validate() {
        require(host.isNotBlank() && host == host.trim() && host.none { it.isWhitespace() || it in "/@?#" }) { "Invalid SOCKS5 host" }
        require(runCatching { okhttp3.HttpUrl.Builder().scheme("http").host(host).build() }.isSuccess) { "Invalid SOCKS5 host" }
        require(port in 1..65535) { "Invalid SOCKS5 port" }
        require((username.isEmpty() && password.isEmpty()) ||
            (username.toByteArray(Charsets.UTF_8).size in 1..255 && password.toByteArray(Charsets.UTF_8).size in 1..255)) {
            "SOCKS5 username and password must both contain 1–255 UTF-8 bytes, or both be empty"
        }
    }

    override fun toString(): String = "ProviderProxy(enabled=$enabled, host=$host, port=$port, credentials=<redacted>)"
}
