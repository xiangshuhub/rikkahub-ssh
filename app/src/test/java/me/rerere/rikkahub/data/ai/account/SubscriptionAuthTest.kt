package me.rerere.rikkahub.data.ai.account

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.AccountService
import me.rerere.ai.provider.OAuthCredential
import me.rerere.oauth.OAuthHttpClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class SubscriptionAuthTest {
    private fun client(body: String, onRequest: (Request) -> Unit = {}) = OkHttpClient.Builder()
        .addInterceptor { chain ->
            onRequest(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()

    @Test
    fun `manual input supports urls queries and copy codes and rejects mismatched state`() {
        assertEquals("abc", parseAuthorizationInput("abc", "state"))
        assertEquals("abc", parseAuthorizationInput("abc#state", "state"))
        assertEquals("abc", parseAuthorizationInput("http://localhost/callback?code=abc&state=state", "state"))
        assertEquals("abc", parseAuthorizationInput("code=abc&state=state", "state"))
        for (input in listOf("abc#wrong", "http://localhost/?code=abc&state=wrong", "", "http://localhost/?error=denied")) {
            assertTrue(runCatching { parseAuthorizationInput(input, "state") }.isFailure)
        }
    }

    @Test
    fun `device flows use fixed official endpoints`() {
        for (service in listOf(AccountService.KIMI, AccountService.COPILOT, AccountService.META)) {
            val request = SubscriptionAuth.deviceRequest(service)
            assertTrue(request.deviceAuthorizationEndpoint.startsWith("https://"))
            assertEquals(request.clientId, SubscriptionAuth.deviceTokenRequest(service, "code").clientId)
        }
        assertEquals("read:user", SubscriptionAuth.deviceRequest(AccountService.COPILOT).scope)
        assertTrue(runCatching { SubscriptionAuth.deviceRequest(AccountService.CLAUDE) }.isFailure)
        assertTrue(runCatching { SubscriptionAuth.deviceRequest(AccountService.OPENROUTER) }.isFailure)
    }

    @Test
    fun `Meta identity is exchanged for a separate inference key and retained for renewal`() = runBlocking {
        val transport = client("""{"api_key":"inference-key","subs_usage":{"window":{"used_percent":23}}}""") {
            assertEquals("https://api.meta.ai/muse-code/key", it.url.toString())
            assertEquals("Bearer identity-token", it.header("Authorization"))
            assertEquals("1.0.0", it.header("x-api-version"))
        }
        val result = SubscriptionAuth.finishDevice(transport, AccountService.META, OAuthHttpClient.TokenResponse("identity-token"))
        assertEquals("inference-key", result.accessToken)
        assertEquals("identity-token", result.refreshToken)
        assertEquals("23", result.quotaSnapshot!!["window"]!!.jsonObject["used_percent"]!!.jsonPrimitive.content)
        assertTrue(result.quotaSnapshotAt > 0)
        assertTrue(result.expiresAt > System.currentTimeMillis())
        assertEquals("inference-key", SubscriptionAuth.refresh(transport, AccountService.META, result).accessToken)
    }

    @Test
    fun `Copilot retains GitHub identity but sends the minted token to inference`() = runBlocking {
        val transport = client("""{"token":"copilot-token","expires_at":2000000000}""") {
            assertEquals("api.github.com", it.url.host)
            assertEquals("Bearer github-token", it.header("Authorization"))
            assertEquals("vscode-chat", it.header("Copilot-Integration-Id"))
        }
        val result = SubscriptionAuth.finishDevice(transport, AccountService.COPILOT, OAuthHttpClient.TokenResponse("github-token"))
        assertEquals("copilot-token", result.accessToken)
        assertEquals("github-token", result.refreshToken)
        assertEquals(2_000_000_000_000L, result.expiresAt)
    }

    @Test
    fun `OpenRouter exchanges PKCE for a nonexpiring key`() = runBlocking {
        val result = SubscriptionAuth.exchangeBrowser(client("""{"key":"or-key"}""") {
            assertEquals("https://openrouter.ai/api/v1/auth/keys", it.url.toString())
            val body = Json.parseToJsonElement(Buffer().also { buffer -> it.body!!.writeTo(buffer) }.readUtf8()).jsonObject
            assertEquals("v", body["code_verifier"]!!.jsonPrimitive.content)
            assertEquals("S256", body["code_challenge_method"]!!.jsonPrimitive.content)
        }, AccountService.OPENROUTER, "code", "state", OAuthHttpClient.Pkce("v", "c"), "http://localhost")
        assertEquals("or-key", result.accessToken)
        assertNull(result.refreshToken)
        assertEquals(0L, result.expiresAt)
    }

    @Test
    fun `Claude refresh uses JSON and retains refresh token if not rotated`() = runBlocking {
        val result = SubscriptionAuth.refresh(client("""{"access_token":"new","expires_in":3600}""") {
            assertEquals(SubscriptionAuth.CLAUDE_TOKEN, it.url.toString())
            assertEquals("application/json; charset=utf-8", it.body!!.contentType().toString())
        }, AccountService.CLAUDE, OAuthCredential("old", "refresh"))
        assertEquals("new", result.accessToken)
        assertEquals("refresh", result.refreshToken)
    }
}
