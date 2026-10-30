package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.*
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class SubscriptionProviderTest {
    @Test
    fun `all account settings round trip without losing service or credentials`() {
        for (service in AccountService.entries) {
            val setting = ProviderSetting.Subscription(service, credential = OAuthCredential("access", "refresh"))
            val decoded = json.decodeFromString(ProviderSetting.serializer(), json.encodeToString(ProviderSetting.serializer(), setting))
            assertEquals(setting, decoded)
            assertTrue(decoded.supportsQuota)
            assertFalse(setting.copy(credential = null).supportsQuota)
            assertEquals(service == AccountService.CLAUDE, decoded.supportsBuiltInSearch)
            assertEquals("access", (decoded.copyProvider(name = "renamed") as ProviderSetting.Account).credential!!.accessToken)
        }
    }

    @Test
    fun `Meta quota snapshot survives settings persistence`() {
        val snapshot = json.parseToJsonElement("""{"weekly":{"used_percent":42}}""").jsonObject
        val setting = ProviderSetting.Subscription(AccountService.META,
            credential = OAuthCredential("access", "identity", quotaSnapshot = snapshot, quotaSnapshotAt = 123))
        val restored = json.decodeFromString(ProviderSetting.serializer(), json.encodeToString(ProviderSetting.serializer(), setting)) as ProviderSetting.Subscription
        assertEquals(snapshot, restored.credential!!.quotaSnapshot)
        assertEquals(123L, restored.credential!!.quotaSnapshotAt)
    }

    @Test
    fun `Claude OAuth identity preserves system prompt tools and messages`() {
        val original = json.parseToJsonElement("""{"system":[{"type":"text","text":"Be brief"}],"tools":[{"name":"my_tool"}],"messages":[]}""").jsonObject
        val result = withClaudeOAuthIdentity(original)
        val system = result["system"]!!.jsonArray
        assertEquals(2, system.size)
        assertTrue(system.first().jsonObject["text"]!!.jsonPrimitive.content.startsWith("You are Claude Code"))
        assertEquals("Be brief", system.last().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(original["tools"], result["tools"])
        assertEquals(original["messages"], result["messages"])
    }

    @Test
    fun `Copilot token routes only to official API hosts`() {
        assertEquals("https://api.individual.githubcopilot.com", copilotBaseUrl("opaque"))
        assertEquals("https://api.business.githubcopilot.com", copilotBaseUrl("tid=1;proxy-ep=proxy.business.githubcopilot.com;exp=1"))
        for (host in listOf("evil.test", "proxy.individual.githubcopilot.com.evil.test", "proxy.individual.githubcopilot.com/path")) {
            assertTrue(runCatching { copilotBaseUrl("proxy-ep=$host") }.isFailure)
        }
    }

    @Test
    fun `Copilot catalog excludes disabled policies and falls back to enabled models`() {
        val catalog = kotlinx.serialization.json.Json.parseToJsonElement("""{"data":[
            {"id":"disabled","model_picker_enabled":true,"policy":{"state":"disabled"}},
            {"id":"enabled","policy":{"state":"enabled"}},
            {"id":"unavailable","policy":{"state":"unconfigured"}}
        ]}""") as kotlinx.serialization.json.JsonObject
        val result = filterCopilotCatalog(catalog).toString()
        assertTrue(result.contains("enabled"))
        assertFalse(result.contains("disabled"))
        assertFalse(result.contains("unavailable"))
    }

    @Test
    fun `catalogs use official endpoints and protected bearer authentication`() = runBlocking {
        for (service in AccountService.entries) {
            var requests = 0
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                val request = chain.request()
                val path = if (service == AccountService.OPENROUTER) "/models/user" else "/models"
                assertEquals("${service.baseUrl}$path", request.url.toString())
                assertEquals("Bearer official-token", request.header("Authorization"))
                assertNull(request.header("x-api-key"))
                if (service == AccountService.CLAUDE) {
                    assertTrue(request.header("anthropic-beta")!!.contains("oauth-2025-04-20"))
                }
                if (service == AccountService.COPILOT) assertEquals("vscode-chat", request.header("Copilot-Integration-Id"))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("""{"data":[{"id":"test-model","model_picker_enabled":true}]}""".toResponseBody()).build()
            }.build()
            val provider = SubscriptionProvider(client, null, AccountCredentialProvider { _, _ -> OAuthCredential("official-token") })
            val models = provider.listModels(ProviderSetting.Subscription(service,
                customHeaders = listOf(CustomHeader("Authorization", "Bearer attacker"), CustomHeader("x-api-key", "attacker"))))
            assertEquals(listOf("test-model"), models.map { it.modelId })
            assertEquals(1, requests)
        }
    }
}
