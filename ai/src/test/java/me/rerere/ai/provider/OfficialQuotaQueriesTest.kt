package me.rerere.ai.provider

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class OfficialQuotaQueriesTest {
    @Test fun `deepseek legacy and generic queries use documented root endpoint and balance field`() = runTest {
        for (query in listOf(
            QuotaQuery(enabled = true, url = "{{baseUrl}}/user/balance", resultPath = "balance_infos[0].total_balance"),
            QuotaQuery(enabled = true),
            QuotaQuery(enabled = true, url = "{{origin}}/user/balance", resultPath = "balance_infos[0].total_balance"),
        )) {
            var calls = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                assertEquals("https://api.deepseek.com/user/balance", chain.request().url.toString())
                assertEquals("Bearer test-key", chain.request().header("Authorization"))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"12.34","granted_balance":"2.34","topped_up_balance":"10.00"}]}""".toResponseBody()).build()
            }.build()
            val provider = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1", quotaQuery = query)
            assertEquals(ProviderQuota.Balance("%.2f".format(12.34f)), client.runQuotaQuery(provider) { "test-key" })
            assertEquals(1, calls)
            assertEquals(query.enabled, provider.effectiveQuotaQuery.enabled)
        }
    }

    @Test fun `migration retains switch headers and custom result expressions`() {
        val query = QuotaQuery(enabled = false, url = "{{baseUrl}}/user/balance",
            headers = listOf(CustomHeader("X-Custom", "preserve")), resultPath = "balance_infos[1].total_balance")
        val fixed = ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1", quotaQuery = query).effectiveQuotaQuery
        assertEquals("{{origin}}/user/balance", fixed.url)
        assertFalse(fixed.enabled)
        assertEquals(query.headers, fixed.headers)
        assertEquals(query.resultPath, fixed.resultPath)
    }

    @Test fun `custom proxies and custom queries are never redirected to deepseek`() {
        val query = QuotaQuery(enabled = true, url = "{{baseUrl}}/user/balance", resultPath = "balance_infos[0].total_balance")
        for (url in listOf("https://proxy.example/v1", "https://api.deepseek.com.evil.test/v1", "https://api.deepseek.com@evil.test/v1")) {
            val provider = ProviderSetting.OpenAI(name = "DeepSeek", baseUrl = url, quotaQuery = query)
            assertEquals(query, provider.effectiveQuotaQuery)
        }
        val custom = query.copy(url = "https://custom.example/balance")
        assertEquals(custom, ProviderSetting.OpenAI(baseUrl = "https://api.deepseek.com/v1", quotaQuery = custom).effectiveQuotaQuery)
    }

    @Test fun `origin preserves custom hosts ports and ipv6 without paths or userinfo`() {
        assertEquals("https://proxy.example:8443", quotaOrigin("https://user:password@proxy.example:8443/api/v1?x=1"))
        assertEquals("https://[::1]:8443", quotaOrigin("https://[::1]:8443/v1"))
    }

    @Test fun `retired mainland siliconflow queries do not send credentials or network requests`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { error("No request expected") }.build()
        for (url in listOf("{{baseUrl}}/credits", "{{baseUrl}}/user/info", "https://api.siliconflow.cn/v1/user/info")) {
            val provider = ProviderSetting.OpenAI(baseUrl = "https://api.siliconflow.cn/v1", quotaQuery = QuotaQuery(enabled = true, url = url))
            assertEquals(ProviderQuota.Website(SILICONFLOW_CONSOLE_URL), client.runQuotaQuery(provider) { error("No credentials expected") })
        }
    }

    @Test fun `international siliconflow and custom balance endpoints remain unchanged`() {
        val query = QuotaQuery(enabled = true, url = "{{baseUrl}}/user/info", resultPath = "data.totalBalance")
        assertFalse(ProviderSetting.OpenAI(baseUrl = "https://api.siliconflow.com/v1", quotaQuery = query).hasUnsupportedSiliconFlowQuota)
        assertFalse(ProviderSetting.OpenAI(baseUrl = "https://api.siliconflow.cn/v1", quotaQuery = query.copy(url = "https://custom.example/balance")).hasUnsupportedSiliconFlowQuota)
    }
}
