package me.rerere.ai.provider

import android.content.Context
import me.rerere.ai.provider.providers.SubscriptionProvider
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.provider.providers.grok.GrokProvider
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import okhttp3.OkHttpClient

/**
 * Provider管理器，负责注册和获取Provider实例
 */
class ProviderManager(
    private val client: OkHttpClient,
    private val context: Context?,
    private val accountCredentials: AccountCredentialProvider,
) {
    // 存储已注册的Provider实例
    private val providers = mutableMapOf<String, Provider<*>>()
    private data class Proxied(val config: ProviderProxy, val type: String, val client: OkHttpClient, val provider: Provider<*>)
    private val proxied = LinkedHashMap<kotlin.uuid.Uuid, Proxied>(16, 0.75f, true)

    init {
        // 注册默认Provider
        registerProvider("openai", OpenAIProvider(client, context))
        registerProvider("google", GoogleProvider(client, context))
        registerProvider("claude", ClaudeProvider(client, context))
        registerProvider("chatgpt", ChatGPTProvider(client, accountCredentials))
        registerProvider("grok", GrokProvider(client, accountCredentials))
        registerProvider("subscription", SubscriptionProvider(client, context, accountCredentials))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : ProviderSetting> createProvider(setting: T, transport: OkHttpClient): Provider<T> = when (setting) {
        is ProviderSetting.OpenAI -> OpenAIProvider(transport, context)
        is ProviderSetting.Google -> GoogleProvider(transport, context)
        is ProviderSetting.Claude -> ClaudeProvider(transport, context)
        is ProviderSetting.ChatGPT -> ChatGPTProvider(transport, accountCredentials)
        is ProviderSetting.Grok -> GrokProvider(transport, accountCredentials)
        is ProviderSetting.Subscription -> SubscriptionProvider(transport, context, accountCredentials)
    } as Provider<T>

    /**
     * 注册Provider实例
     *
     * @param name Provider名称
     * @param provider Provider实例
     */
    fun registerProvider(name: String, provider: Provider<*>) {
        providers[name] = provider
    }

    /**
     * 获取Provider实例
     *
     * @param name Provider名称
     * @return Provider实例，如果不存在则返回null
     */
    fun getProvider(name: String): Provider<*> {
        return providers[name] ?: throw IllegalArgumentException("Provider not found: $name")
    }

    /**
     * 根据ProviderSetting获取对应的Provider实例
     *
     * @param setting Provider设置
     * @return Provider实例，如果不存在则返回null
     */
    @Synchronized
    fun <T : ProviderSetting> getProviderByType(setting: T): Provider<T> {
        val type = setting.javaClass.name
        if (setting.proxy.enabled) {
            val cached = proxied[setting.id]
            if (cached?.config != setting.proxy || cached.type != type) {
                val transport = client.withProviderProxy(setting.proxy)
                val provider = createProvider(setting, transport)
                cached?.client?.connectionPool?.evictAll()
                proxied[setting.id] = Proxied(setting.proxy, type, transport, provider)
                if (proxied.size > 32) {
                    val oldest = proxied.entries.first()
                    oldest.value.client.connectionPool.evictAll()
                    proxied.remove(oldest.key)
                }
            }
            @Suppress("UNCHECKED_CAST")
            return proxied.getValue(setting.id).provider as Provider<T>
        }
        proxied.remove(setting.id)?.client?.connectionPool?.evictAll()
        @Suppress("UNCHECKED_CAST")
        return when (setting) {
            is ProviderSetting.OpenAI -> getProvider("openai")
            is ProviderSetting.Google -> getProvider("google")
            is ProviderSetting.Claude -> getProvider("claude")
            is ProviderSetting.ChatGPT -> getProvider("chatgpt")
            is ProviderSetting.Grok -> getProvider("grok")
            is ProviderSetting.Subscription -> getProvider("subscription")
        } as Provider<T>
    }
}
