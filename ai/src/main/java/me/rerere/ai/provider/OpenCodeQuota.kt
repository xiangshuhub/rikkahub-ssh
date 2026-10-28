package me.rerere.ai.provider

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val OPENCODE_CONSOLE_URL = "https://opencode.ai/console"

/** Exact official pay-as-you-go endpoints only; Go subscriptions and compatible proxies are separate. */
val ProviderSetting.ApiKey.isOpenCodeZen: Boolean
    get() {
        val url = baseUrl.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" && url.host == "opencode.ai" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            url.encodedPath.trimEnd('/') in setOf(
                "/inference/openai/v1", "/inference/anthropic/v1", "/inference/v1", "/zen/v1",
            )
    }

/** The generic /credits template is not a supported Zen balance API. Never guess another endpoint.
 * User-configured alternative query URLs remain intact and use the normal query runner.
 */
internal val ProviderSetting.ApiKey.hasUnsupportedOpenCodeQuotaTemplate: Boolean
    get() {
        if (!isOpenCodeZen) return false
        val rendered = runCatching {
            renderQuotaTemplate(quotaQuery.url.trim()) {
                if (it == QuotaQuery.VARIABLE_BASE_URL) baseUrl.trimEnd('/') else null
            }
        }.getOrNull()?.toHttpUrlOrNull() ?: return false
        val defaultUrl = "${baseUrl.trimEnd('/')}/credits".toHttpUrlOrNull() ?: return false
        return rendered == defaultUrl
    }
