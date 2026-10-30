package me.rerere.rikkahub.data.ai.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.provider.AccountService
import me.rerere.ai.provider.OAuthCredential
import me.rerere.common.http.await
import me.rerere.oauth.OAuthHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Protocol constants follow the corresponding flows in pi/packages/ai/src/auth/oauth. */
internal object SubscriptionAuth {
    const val CLAUDE_CLIENT = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    const val CLAUDE_TOKEN = "https://platform.claude.com/v1/oauth/token"
    const val CLAUDE_COPY_REDIRECT = "https://platform.claude.com/oauth/code/callback"
    const val CLAUDE_SCOPE = "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload"
    private const val KIMI_CLIENT = "17e5f671-d194-4dfb-9706-5516cb48c098"
    private const val KIMI_TOKEN = "https://auth.kimi.com/api/oauth/token"
    private const val COPILOT_CLIENT = "Iv1.b507a08c87ecfe98"
    private const val META_CLIENT = "1031625952748946"

    fun deviceRequest(service: AccountService): OAuthHttpClient.DeviceAuthorizationRequest = when (service) {
        AccountService.KIMI -> OAuthHttpClient.DeviceAuthorizationRequest("https://auth.kimi.com/api/oauth/device_authorization", KIMI_CLIENT)
        AccountService.COPILOT -> OAuthHttpClient.DeviceAuthorizationRequest("https://github.com/login/device/code", COPILOT_CLIENT, "read:user")
        AccountService.META -> OAuthHttpClient.DeviceAuthorizationRequest("https://auth.meta.com/oidc/device/authorization/", META_CLIENT)
        else -> error("Not a device-code provider")
    }

    fun deviceTokenRequest(service: AccountService, code: String): OAuthHttpClient.DeviceTokenRequest = when (service) {
        AccountService.KIMI -> OAuthHttpClient.DeviceTokenRequest(KIMI_TOKEN, KIMI_CLIENT, code)
        AccountService.COPILOT -> OAuthHttpClient.DeviceTokenRequest("https://github.com/login/oauth/access_token", COPILOT_CLIENT, code)
        AccountService.META -> OAuthHttpClient.DeviceTokenRequest("https://auth.meta.com/oidc/device/token/", META_CLIENT, code)
        else -> error("Not a device-code provider")
    }

    fun credential(token: OAuthHttpClient.TokenResponse, previous: OAuthCredential? = null): OAuthCredential {
        require(token.accessToken.isNotBlank()) { "Missing access token" }
        return OAuthCredential(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken ?: previous?.refreshToken,
            expiresAt = token.expiresIn?.let { System.currentTimeMillis() + it * 1000 } ?: 0,
        )
    }

    suspend fun finishDevice(client: OkHttpClient, service: AccountService, token: OAuthHttpClient.TokenResponse): OAuthCredential = when (service) {
        AccountService.COPILOT -> mintCopilot(client, token.accessToken)
        AccountService.META -> mintMeta(client, token.accessToken)
        else -> credential(token)
    }

    suspend fun refresh(client: OkHttpClient, service: AccountService, previous: OAuthCredential): OAuthCredential {
        val refresh = previous.refreshToken ?: error("Session expired; sign in again")
        return when (service) {
            AccountService.COPILOT -> mintCopilot(client, refresh)
            AccountService.META -> mintMeta(client, refresh)
            AccountService.KIMI, AccountService.CLAUDE -> credential(
                OAuthHttpClient(client).refreshToken(OAuthHttpClient.RefreshTokenRequest(
                    tokenEndpoint = if (service == AccountService.KIMI) KIMI_TOKEN else CLAUDE_TOKEN,
                    clientId = if (service == AccountService.KIMI) KIMI_CLIENT else CLAUDE_CLIENT,
                    refreshToken = refresh,
                    encoding = if (service == AccountService.KIMI) OAuthHttpClient.TokenEncoding.FORM else OAuthHttpClient.TokenEncoding.JSON,
                )), previous,
            )
            AccountService.OPENROUTER -> error("OpenRouter key rejected; sign in again")
        }
    }

    private suspend fun mintCopilot(client: OkHttpClient, githubToken: String): OAuthCredential {
        val data = requestJson(client, Request.Builder()
            .url("https://api.github.com/copilot_internal/v2/token")
            .header("Authorization", "Bearer $githubToken")
            .header("User-Agent", "GitHubCopilotChat/0.35.0")
            .header("Editor-Version", "vscode/1.107.0")
            .header("Editor-Plugin-Version", "copilot-chat/0.35.0")
            .header("Copilot-Integration-Id", "vscode-chat").build())
        return OAuthCredential(data.requiredString("token"), githubToken,
            (data["expires_at"]?.jsonPrimitive?.longOrNull ?: error("Missing Copilot expiration")) * 1000)
    }

    private suspend fun mintMeta(client: OkHttpClient, identity: String): OAuthCredential {
        val data = postJson(client, "https://api.meta.ai/muse-code/key", buildJsonObject {}, mapOf(
            "Authorization" to "Bearer $identity", "x-api-version" to "1.0.0",
        ))
        val now = System.currentTimeMillis()
        return OAuthCredential(
            accessToken = data.requiredString("api_key"), refreshToken = identity, expiresAt = now + 86_400_000,
            quotaSnapshot = data["subs_usage"] as? JsonObject,
            quotaSnapshotAt = now,
        )
    }

    suspend fun exchangeBrowser(client: OkHttpClient, service: AccountService, code: String, state: String, pkce: OAuthHttpClient.Pkce, redirect: String): OAuthCredential {
        val data = if (service == AccountService.CLAUDE) postJson(client, CLAUDE_TOKEN, buildJsonObject {
            put("grant_type", "authorization_code")
            put("client_id", CLAUDE_CLIENT)
            put("code", code)
            put("state", state)
            put("redirect_uri", redirect)
            put("code_verifier", pkce.verifier)
        }) else postJson(client, "https://openrouter.ai/api/v1/auth/keys", buildJsonObject {
            put("code", code)
            put("code_verifier", pkce.verifier)
            put("code_challenge_method", "S256")
        })
        if (service == AccountService.OPENROUTER) return OAuthCredential(data.requiredString("key"))
        return credential(Json { ignoreUnknownKeys = true }.decodeFromJsonElement<OAuthHttpClient.TokenResponse>(data))
    }

    private suspend fun postJson(client: OkHttpClient, url: String, body: JsonObject, headers: Map<String, String> = emptyMap()): JsonObject =
        requestJson(client, Request.Builder().url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())

    private suspend fun requestJson(client: OkHttpClient, request: Request): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(request.newBuilder().header("Accept", "application/json").build()).await().use { response ->
            // Never expose token response bodies in logs or UI errors.
            check(response.isSuccessful) { "${request.url.host}: HTTP ${response.code}; check account permissions or sign in again" }
            Json.parseToJsonElement(response.body.string()).jsonObject
        }
    }

    private fun JsonObject.requiredString(key: String): String =
        get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("Missing $key in authorization response")
}
