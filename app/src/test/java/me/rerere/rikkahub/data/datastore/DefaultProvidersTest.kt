package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultProvidersTest {
    @Test
    fun `legacy zen preset migrates without losing user data or forcing future choices`() {
        val preset = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.OpenAI>()
            .single { it.name == "OpenCode Zen · Responses" }
        val legacy = preset.copy(name = "OpenCode Zen · Chat Completions", useResponseApi = false,
            apiKey = "test-placeholder", enabled = true, models = preset.models.take(1))
        val migrated = Settings(providers = listOf(legacy)).withBuiltInDefaults().providers
            .single { it.id == preset.id } as ProviderSetting.OpenAI
        assertTrue(migrated.useResponseApi)
        assertEquals("OpenCode Zen · Responses", migrated.name)
        assertEquals(legacy.apiKey, migrated.apiKey)
        assertEquals(legacy.models, migrated.models)
        assertTrue(migrated.enabled)
        val manual = migrated.copy(useResponseApi = false)
        val preserved = Settings(providers = listOf(manual)).withBuiltInDefaults().providers
            .single { it.id == preset.id } as ProviderSetting.OpenAI
        assertFalse(preserved.useResponseApi)
        val custom = legacy.copy(baseUrl = "https://example.com/v1")
        val untouched = Settings(providers = listOf(custom)).withBuiltInDefaults().providers
            .single { it.id == preset.id } as ProviderSetting.OpenAI
        assertFalse(untouched.useResponseApi)
    }

    @Test
    fun `zen presets should use separate protocols and matching models`() {
        val zen = DEFAULT_PROVIDERS.filter { it.name.startsWith("OpenCode Zen ·") }
        assertEquals(2, zen.size)
        zen.forEach {
            assertTrue(it.builtIn)
            assertFalse(it.enabled)
        }
        val claude = zen.filterIsInstance<ProviderSetting.Claude>().single()
        assertEquals("https://opencode.ai/inference/anthropic/v1", claude.baseUrl)
        assertEquals("claude-haiku-5-5", claude.models.single().modelId)
        assertEquals("", claude.apiKey)

        val chat = zen.single { it.name.endsWith("Responses") } as ProviderSetting.OpenAI
        assertTrue(chat.useResponseApi)
        assertEquals("https://opencode.ai/inference/openai/v1", chat.baseUrl)
        assertEquals(listOf("deepseek-v4.1-flash", "glm-5.3-flash"), chat.models.map { it.modelId })
        assertEquals("", chat.apiKey)
        val modelIds = zen.flatMap { it.models }.map { it.id }
        assertEquals(modelIds.size, modelIds.distinct().size)
    }

    @Test
    fun `default providers should include disabled codex and grok accounts`() {
        val codex = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.ChatGPT>().single()
        assertEquals(DEFAULT_CODEX_PROVIDER_ID, codex.id)
        assertEquals("Codex", codex.name)
        assertFalse(codex.enabled)
        assertTrue(codex.builtIn)

        val grok = DEFAULT_PROVIDERS.filterIsInstance<ProviderSetting.Grok>().single()
        assertEquals(DEFAULT_GROK_PROVIDER_ID, grok.id)
        assertEquals("Grok", grok.name)
        assertFalse(grok.enabled)
        assertTrue(grok.builtIn)
        assertEquals(DEFAULT_PROVIDERS.size, DEFAULT_PROVIDERS.map { it.id }.distinct().size)
    }

    @Test
    fun `default providers should include vercel ai gateway with expected balance config`() {
        val vercelProviders = DEFAULT_PROVIDERS
            .filterIsInstance<ProviderSetting.OpenAI>()
            .filter { it.name == "Vercel AI Gateway" }

        assertEquals(1, vercelProviders.size)

        val provider = vercelProviders.single()
        assertEquals("https://ai-gateway.vercel.sh/v1", provider.baseUrl)
        assertFalse(provider.enabled)
        assertTrue(provider.builtIn)
        assertTrue(provider.quotaQuery.enabled)
        assertEquals("{{baseUrl}}/credits", provider.quotaQuery.url)
        assertEquals("balance", provider.quotaQuery.resultPath)
    }
}
