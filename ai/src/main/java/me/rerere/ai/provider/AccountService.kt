package me.rerere.ai.provider

import kotlinx.serialization.Serializable

/** Fixed official routes: account credentials must never follow a user-supplied base URL. */
@Serializable
enum class AccountService(val displayName: String, val baseUrl: String) {
    CLAUDE("Claude Pro / Max", "https://api.anthropic.com/v1"),
    COPILOT("GitHub Copilot", "https://api.individual.githubcopilot.com"),
    KIMI("Kimi Code", "https://api.kimi.com/coding/v1"),
    META("Meta Muse", "https://api.meta.ai/v1"),
    OPENROUTER("OpenRouter OAuth", "https://openrouter.ai/api/v1"),
}
