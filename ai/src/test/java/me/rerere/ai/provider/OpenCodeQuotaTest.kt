package me.rerere.ai.provider

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class OpenCodeQuotaTest {
    private val noNetwork = OkHttpClient.Builder().addInterceptor { error("Must not query the unsupported endpoint") }.build()

    @Test fun `official endpoints use website without reading keys or making requests`() = runTest {
        for (path in listOf("/inference/openai/v1", "/inference/anthropic/v1", "/inference/v1", "/zen/v1")) {
            val setting = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai$path", quotaQuery = QuotaQuery(enabled = true))
            assertTrue(setting.isOpenCodeZen)
            assertEquals(ProviderQuota.Website(OPENCODE_CONSOLE_URL), noNetwork.runQuotaQuery(setting) { error("Must not access credentials") })
        }
    }

    @Test fun `claude and trailing slash or absolute default queries are handled`() = runTest {
        val setting = ProviderSetting.Claude(baseUrl = "https://opencode.ai/inference/anthropic/v1/")
        val query = setting.quotaQuery.copy(enabled = true)
        assertEquals(ProviderQuota.Website(OPENCODE_CONSOLE_URL), noNetwork.runQuotaQuery(setting.copy(quotaQuery = query)) { error("No credentials") })
        assertTrue(setting.copy(quotaQuery = query.copy(url = "https://opencode.ai/inference/anthropic/v1/credits")).hasUnsupportedOpenCodeQuotaTemplate)
    }

    @Test fun `disabled zen balance query stays disabled`() = runTest {
        val setting = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/inference/openai/v1")
        assertNull(noNetwork.runQuotaQuery(setting) { error("No credentials") })
    }

    @Test fun `go proxies and misleading host names are not classified as zen`() {
        for (url in listOf("https://proxy.example/zen/v1", "https://opencode.ai.evil.test/zen/v1",
            "https://opencode.ai@evil.test/zen/v1", "https://opencode.ai/zen/go/v1", "https://opencode.ai/other/v1")) {
            assertFalse(ProviderSetting.OpenAI(name = "OpenCode Zen", baseUrl = url).isOpenCodeZen)
        }
    }

    @Test fun `custom query is preserved and executed normally`() = runTest {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("https://custom.example/balance", chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"remaining":"custom-balance"}""".toResponseBody()).build()
        }.build()
        val query = QuotaQuery(enabled = true, url = "https://custom.example/balance", resultPath = "remaining")
        val setting = ProviderSetting.OpenAI(baseUrl = "https://opencode.ai/inference/openai/v1", quotaQuery = query)
        assertFalse(setting.hasUnsupportedOpenCodeQuotaTemplate)
        assertEquals(ProviderQuota.Balance("custom-balance"), client.runQuotaQuery(setting) { "test-key" })
        assertEquals(1, requests)
        assertEquals(query, setting.quotaQuery)
    }
}
