package me.rerere.ai.provider.providers

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.AccountService
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Reuses the existing streaming/tool codecs, but owns routing and authentication. */
class SubscriptionProvider(
    private val client: OkHttpClient,
    private val context: Context?,
    private val credentials: AccountCredentialProvider,
) : Provider<ProviderSetting.Subscription> {
    private val quotaQueries = SubscriptionQuotaQueries(client, credentials)

    override suspend fun getQuota(providerSetting: ProviderSetting.Subscription) = quotaQueries.get(providerSetting)

    private suspend fun delegate(
        setting: ProviderSetting.Subscription,
        modelId: String = "",
        messages: List<UIMessage> = emptyList(),
    ): Pair<Provider<ProviderSetting>, ProviderSetting> {
        val credential = credentials.getCredential(setting, null)
        val baseUrl = if (setting.service == AccountService.COPILOT) copilotBaseUrl(credential.accessToken) else setting.service.baseUrl
        val host = baseUrl.toHttpUrl().host
        val transport = client.withAccountAuthenticator(credentials).newBuilder()
            .followRedirects(false).followSslRedirects(false)
            .apply { interceptors().add(0, Interceptor { chain ->
                val request = chain.request()
                check(request.url.host == host && request.url.isHttps) { "Account request outside official endpoint" }
                val builder = request.newBuilder()
                if (setting.service == AccountService.OPENROUTER && request.url.encodedPath.endsWith("/models")) {
                    builder.url(request.url.newBuilder().addPathSegment("user").build())
                }
                runBlocking {
                    builder.configureAccountAuth(credentials, setting, request.headers.map { CustomHeader(it.first, it.second) }) {
                        removeHeader("x-api-key")
                        removeHeader("Host")
                        when (setting.service) {
                            AccountService.CLAUDE -> {
                                header("anthropic-beta", (request.header("anthropic-beta").orEmpty().split(',') +
                                    listOf("claude-code-20250219", "oauth-2025-04-20"))
                                    .filter { it.isNotBlank() }.distinct().joinToString(","))
                                header("anthropic-version", "2023-06-01")
                                header("user-agent", "claude-cli/2.1.280")
                                header("x-app", "cli")
                            }
                            AccountService.COPILOT -> {
                                url(request.url.newBuilder().host(copilotBaseUrl(it.accessToken).toHttpUrl().host).build())
                                header("User-Agent", "GitHubCopilotChat/0.35.0")
                                header("Editor-Version", "vscode/1.107.0")
                                header("Editor-Plugin-Version", "copilot-chat/0.35.0")
                                header("Copilot-Integration-Id", "vscode-chat")
                                header("X-GitHub-Api-Version", "2026-06-01")
                                val lastRole = messages.lastOrNull()?.role
                                header("X-Initiator", if (lastRole != null && lastRole != MessageRole.USER) "agent" else "user")
                                header("Openai-Intent", "conversation-edits")
                                if (messages.any { message -> message.parts.any { part -> part is UIMessagePart.Image } }) {
                                    header("Copilot-Vision-Request", "true")
                                }
                            }
                            else -> Unit
                        }
                    }
                }
                if (setting.service == AccountService.CLAUDE && request.url.encodedPath.endsWith("/messages")) {
                    val body = Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject
                    builder.post(withClaudeOAuthIdentity(body).toString().toRequestBody("application/json".toMediaType()))
                }
                val response = chain.proceed(builder.build())
                if (setting.service == AccountService.COPILOT && request.url.encodedPath == "/models" && response.isSuccessful) {
                    val mediaType = response.body.contentType()
                    val catalog = Json.parseToJsonElement(response.body.string()).jsonObject
                    response.newBuilder().body(filterCopilotCatalog(catalog).toString().toResponseBody(mediaType)).build()
                } else response
            }) }.build()
        val apiSetting: ProviderSetting
        val provider: Provider<*>
        if (setting.service == AccountService.CLAUDE || (setting.service == AccountService.COPILOT && modelId.startsWith("claude"))) {
            apiSetting = ProviderSetting.Claude(id = setting.id,
                baseUrl = if (setting.service == AccountService.COPILOT) "$baseUrl/v1" else baseUrl,
                apiKey = "account-auth", models = setting.models)
            provider = ClaudeProvider(transport, context)
        } else {
            apiSetting = ProviderSetting.OpenAI(id = setting.id, baseUrl = baseUrl, apiKey = "account-auth", models = setting.models,
                useResponseApi = setting.service == AccountService.COPILOT &&
                    listOf("gpt-", "grok-", "oswe", "mai-").any(modelId::startsWith))
            provider = OpenAIProvider(transport, context)
        }
        @Suppress("UNCHECKED_CAST")
        return (provider as Provider<ProviderSetting>) to apiSetting
    }

    override suspend fun listModels(providerSetting: ProviderSetting.Subscription): List<Model> {
        val (provider, setting) = delegate(providerSetting)
        return provider.listModels(setting)
    }

    override suspend fun generateText(providerSetting: ProviderSetting.Subscription, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult {
        val (provider, setting) = delegate(providerSetting, params.model.modelId, messages)
        return provider.generateText(setting, messages, params)
    }

    override suspend fun streamText(providerSetting: ProviderSetting.Subscription, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> {
        val (provider, setting) = delegate(providerSetting, params.model.modelId, messages)
        return provider.streamText(setting, messages, params)
    }
}

internal fun withClaudeOAuthIdentity(body: JsonObject): JsonObject {
    val identity = buildJsonObject {
        put("type", "text")
        put("text", "You are Claude Code, Anthropic's official CLI for Claude.")
    }
    val blocks = when (val system = body["system"]) {
        is JsonArray -> system.toList()
        is JsonPrimitive -> listOf(buildJsonObject { put("type", "text"); put("text", system.content) })
        else -> emptyList()
    }
    return JsonObject(body + ("system" to JsonArray(listOf(identity) + blocks)))
}

/** Do not offer disabled models or silently enable policies on the user's account. */
internal fun filterCopilotCatalog(catalog: JsonObject): JsonObject {
    val entries = (catalog["data"] as? JsonArray)?.mapNotNull { it as? JsonObject }
        ?: error("Invalid Copilot model catalog")
    fun policy(model: JsonObject) = ((model["policy"] as? JsonObject)?.get("state") as? JsonPrimitive)?.contentOrNull
    val eligible = entries.filter {
        val supports = (it["capabilities"] as? JsonObject)?.get("supports") as? JsonObject
        policy(it) != "disabled" && (supports?.get("tool_calls") as? JsonPrimitive)?.booleanOrNull != false
    }
    val picker = eligible.filter { (it["model_picker_enabled"] as? JsonPrimitive)?.booleanOrNull == true }
    val models = picker.ifEmpty { eligible.filter { policy(it) == "enabled" } }
    return JsonObject(catalog + ("data" to JsonArray(models)))
}

internal fun copilotBaseUrl(token: String): String {
    val proxy = Regex("(?:^|;)proxy-ep=([^;]+)").find(token)?.groupValues?.get(1)
        ?: return AccountService.COPILOT.baseUrl
    val host = proxy.replaceFirst("proxy.", "api.")
    require(host.matches(Regex("api\\.[a-z0-9.-]+\\.githubcopilot\\.com"))) { "Untrusted Copilot endpoint" }
    return "https://$host"
}
