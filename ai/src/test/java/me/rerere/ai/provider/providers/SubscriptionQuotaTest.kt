package me.rerere.ai.provider.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.AccountService
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SubscriptionQuotaTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun parse(service: AccountService, text: String) = parseSubscriptionQuota(service, obj(text), 123L)
    private val kimiBody = """{"usage":{"limit":"100","remaining":"74","resetTime":"2026-11-01T00:00:00Z"},"limits":[{"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"},"detail":{"limit":"100","used":"13","remaining":"87"}}]}"""
    private fun client(respond: (Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (status, body) = respond(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(body.toResponseBody()).build()
    }.build()

    @Test
    fun `Claude percent remains on reported scale and null buckets are omitted`() {
        val quota = parse(AccountService.CLAUDE, """{"five_hour":{"utilization":0.5,"resets_at":"reset"},"seven_day":{"utilization":1.0},"seven_day_opus":null}""")
        assertEquals(listOf(0.5, 1.0), quota.metrics.map { it.usedPercent })
        assertEquals("reset", quota.metrics[0].resetsAt)
        assertEquals(123L, quota.checkedAt)
    }

    @Test
    fun `Copilot preserves remaining count and explicit unlimited status`() {
        val quota = parse(AccountService.COPILOT, """{"quota_reset_date":"2026-11-01","quota_snapshots":{"premium_interactions":{"entitlement":300,"remaining":240},"chat":{"unlimited":true},"completions":null}}""")
        assertEquals(20.0, quota.metrics[0].usedPercent!!, 0.001)
        assertEquals(240.0, quota.metrics[0].remaining!!, 0.001)
        assertEquals("2026-11-01", quota.metrics[0].resetsAt)
        assertTrue(quota.metrics[1].unlimited)
        assertNull(quota.metrics[1].usedPercent)
    }

    @Test
    fun `Kimi numeric strings and rolling windows are parsed without inventing totals`() {
        val quota = parse(AccountService.KIMI, kimiBody)
        assertEquals(26.0, quota.metrics[0].usedPercent!!, 0.001)
        assertEquals("300 分钟", quota.metrics[1].label)
        assertEquals(13.0, quota.metrics[1].usedPercent!!, 0.001)
        val partial = parse(AccountService.KIMI, """{"usage":{"remaining":"42"}}""").metrics.single()
        assertEquals(42.0, partial.remaining!!, 0.001)
        assertNull(partial.usedPercent)
        assertNull(partial.used)
        assertNull(parse(AccountService.KIMI, """{"usage":{"limit":0,"remaining":0}}""").metrics.single().usedPercent)
    }

    @Test
    fun `OpenRouter key budget is not an account balance and null limit is explicit`() {
        val quota = parse(AccountService.OPENROUTER, """{"data":{"limit":null,"limit_remaining":null,"usage":12.5,"usage_daily":0,"budgets":[{"scope":"workspace","limit_usd":100,"remaining_usd":-2,"spend_usd":102}]}}""")
        assertTrue(quota.metrics[0].unlimited)
        assertEquals("USD", quota.metrics[0].unit)
        assertNull(quota.metrics[0].used) // Cumulative usage is not the current budget window's spend.
        assertNull(quota.metrics[0].remaining)
        assertEquals("累计使用", quota.metrics[1].label)
        assertEquals(12.5, quota.metrics[1].used!!, 0.001)
        assertEquals(0.0, quota.metrics[2].used!!, 0.001)
        assertEquals(-2.0, quota.metrics[3].remaining!!, 0.001)
        assertTrue(quota.note!!.contains("不是账号余额"))
        assertFalse(parse(AccountService.OPENROUTER, """{"data":{"usage":2}}""").metrics.single().unlimited)
    }

    @Test
    fun `unknown schemas do not become zero or unlimited`() {
        for (service in AccountService.entries) {
            assertTrue(service.name, runCatching { parse(service, "{}") }.isFailure)
        }
        assertTrue(runCatching { parse(AccountService.CLAUDE, """{"five_hour":{"utilization":"NaN"}}""") }.isFailure)
        assertTrue(runCatching { parse(AccountService.OPENROUTER, """{"data":{}}""") }.isFailure)
    }

    @Test
    fun `Meta quota reads persisted snapshot and never mints or refreshes a token`() = runBlocking {
        val credential = OAuthCredential("expired-access", "identity", expiresAt = 1,
            quotaSnapshot = obj("""{"window":{"used_percent":12,"resets_at":"reset"},"weekly":{"used_percent":30}}"""), quotaSnapshotAt = 42)
        val queries = SubscriptionQuotaQueries(client { error("No network allowed") }, AccountCredentialProvider { _, _ -> error("No refresh allowed") })
        val setting = ProviderSetting.Subscription(AccountService.META, credential = credential)
        repeat(2) {
            val result = queries.get(setting)
            assertEquals(42L, result.checkedAt)
            assertEquals(listOf(12.0, 30.0), result.metrics.map { it.usedPercent })
        }
        assertTrue(runCatching { queries.get(setting.copy(credential = credential.copy(quotaSnapshot = null))) }.isFailure)
    }

    @Test
    fun `parallel requests share short cache which is invalidated by changed credential`() = runBlocking {
        var requests = 0
        val queries = SubscriptionQuotaQueries(client {
            requests++
            assertEquals("https://api.kimi.com/coding/v1/usages", it.url.toString())
            200 to kimiBody
        }, AccountCredentialProvider { setting, _ -> setting.credential!! })
        val setting = ProviderSetting.Subscription(AccountService.KIMI, credential = OAuthCredential("one"))
        List(5) { async { queries.get(setting) } }.awaitAll()
        assertEquals(1, requests)
        queries.get(setting.copy(credential = OAuthCredential("two")))
        assertEquals(2, requests)
    }

    @Test
    fun `Claude and OpenRouter use fixed read-only routes with the correct token`() = runBlocking {
        for ((service, url, body) in listOf(
            Triple(AccountService.CLAUDE, "https://api.anthropic.com/api/oauth/usage", """{"five_hour":{"utilization":20}}"""),
            Triple(AccountService.OPENROUTER, "https://openrouter.ai/api/v1/key", """{"data":{"limit":10,"limit_remaining":8}}"""),
        )) {
            val queries = SubscriptionQuotaQueries(client {
                assertEquals(url, it.url.toString())
                assertEquals("GET", it.method)
                assertEquals("Bearer access", it.header("Authorization"))
                if (service == AccountService.CLAUDE) assertEquals("oauth-2025-04-20", it.header("anthropic-beta"))
                200 to body
            }, AccountCredentialProvider { setting, _ -> setting.credential!! })
            assertEquals(1, queries.get(ProviderSetting.Subscription(service, credential = OAuthCredential("access"))).metrics.size)
        }
    }

    @Test
    fun `malformed responses do not expose their content to the UI`() = runBlocking {
        val queries = SubscriptionQuotaQueries(client { 200 to "secret-invalid-json" },
            AccountCredentialProvider { setting, _ -> setting.credential!! })
        val error = runCatching { queries.get(ProviderSetting.Subscription(AccountService.KIMI,
            credential = OAuthCredential("access"))) }.exceptionOrNull()!!
        assertFalse(error.message!!.contains("secret-invalid-json"))
    }

    @Test
    fun `Copilot quota uses GitHub identity rather than the inference token`() = runBlocking {
        val queries = SubscriptionQuotaQueries(client {
            assertEquals("https://api.github.com/copilot_internal/user", it.url.toString())
            assertEquals("Bearer github-identity", it.header("Authorization"))
            assertEquals("vscode-chat", it.header("Copilot-Integration-Id"))
            200 to """{"quota_snapshots":{"chat":{"unlimited":true}}}"""
        }, AccountCredentialProvider { _, _ -> error("No mint needed to query GitHub identity") })
        val result = queries.get(ProviderSetting.Subscription(AccountService.COPILOT, credential = OAuthCredential("inference", "github-identity")))
        assertTrue(result.metrics.single().unlimited)
    }

    @Test
    fun `401 refreshes at most once and repeated failures are cached without response secrets`() = runBlocking {
        var requests = 0
        var refreshes = 0
        val queries = SubscriptionQuotaQueries(client {
            requests++
            assertEquals(if (requests == 1) "Bearer old" else "Bearer new", it.header("Authorization"))
            401 to "secret-server-body"
        }, AccountCredentialProvider { setting, rejected ->
            if (rejected != null) { refreshes++; OAuthCredential("new") } else setting.credential!!
        })
        val setting = ProviderSetting.Subscription(AccountService.KIMI, credential = OAuthCredential("old", "refresh"))
        repeat(2) {
            val error = runCatching { queries.get(setting) }.exceptionOrNull()!!
            assertFalse(error.message!!.contains("secret-server-body"))
        }
        assertEquals(2, requests)
        assertEquals(1, refreshes)
    }

    @Test
    fun `cache expires after one minute and rate limit failure after thirty seconds`() = runBlocking {
        var now = 0L
        var requests = 0
        var status = 200
        val queries = SubscriptionQuotaQueries(client {
            requests++
            status to if (status == 200) kimiBody else "secret-error-body"
        }, AccountCredentialProvider { setting, _ -> setting.credential!! }, nanoTime = { now })
        val setting = ProviderSetting.Subscription(AccountService.KIMI, credential = OAuthCredential("access"))
        queries.get(setting)
        now = 59_000_000_000
        queries.get(setting)
        assertEquals(1, requests)
        status = 429
        now = 60_000_000_000
        val error = runCatching { queries.get(setting) }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("限流"))
        now = 89_000_000_000
        assertTrue(runCatching { queries.get(setting) }.isFailure)
        assertEquals(2, requests)
        status = 200
        now = 90_000_000_000
        queries.get(setting)
        assertEquals(3, requests)
    }

    @Test
    fun `cancellation does not turn into a cached quota failure`() = runBlocking {
        var calls = 0
        val queries = SubscriptionQuotaQueries(client { 200 to kimiBody }, AccountCredentialProvider { setting, _ ->
            calls++
            if (calls == 1) throw CancellationException("cancelled")
            setting.credential!!
        })
        val setting = ProviderSetting.Subscription(AccountService.KIMI, credential = OAuthCredential("access"))
        assertTrue(runCatching { queries.get(setting) }.exceptionOrNull() is CancellationException)
        assertEquals(2, queries.get(setting).metrics.size)
        assertEquals(2, calls)
    }
}
