package me.rerere.ai.provider

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val SILICONFLOW_CONSOLE_URL = "https://cloud.siliconflow.cn/"

internal fun quotaOrigin(baseUrl: String): String = baseUrl.toHttpUrl().newBuilder()
    .username("").password("").encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')

private fun ProviderSetting.ApiKey.officialBase(hosts: Set<String>, paths: Set<String>): HttpUrl? =
    baseUrl.toHttpUrlOrNull()?.takeIf {
        it.scheme == "https" && it.host in hosts && it.port == 443 &&
            it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null &&
            it.encodedPath.trimEnd('/') in paths
    }

private fun ProviderSetting.ApiKey.queryUrl(): HttpUrl? = runCatching {
    renderQuotaTemplate(quotaQuery.url.trim()) {
        when (it) {
            QuotaQuery.VARIABLE_BASE_URL -> baseUrl.trimEnd('/')
            QuotaQuery.VARIABLE_ORIGIN -> quotaOrigin(baseUrl)
            else -> null
        }
    }.toHttpUrlOrNull()
}.getOrNull()

/** Migrate only recognized official DeepSeek defaults. Custom queries and proxies are untouched. */
val ProviderSetting.ApiKey.effectiveQuotaQuery: QuotaQuery
    get() {
        val base = officialBase(setOf("api.deepseek.com"), setOf("", "/v1", "/anthropic")) ?: return quotaQuery
        val query = queryUrl() ?: return quotaQuery
        if (query.scheme != base.scheme || query.host != base.host || query.port != base.port ||
            query.username.isNotEmpty() || query.password.isNotEmpty() || query.query != null || query.fragment != null
        ) return quotaQuery
        val genericDefault = query.encodedPath in setOf("/credits", "/v1/credits", "/anthropic/credits") &&
            quotaQuery.resultPath == QuotaQuery().resultPath
        val legacyBalance = query.encodedPath in setOf("/v1/user/balance", "/anthropic/user/balance")
        if (!genericDefault && !legacyBalance) return quotaQuery
        return quotaQuery.copy(
            url = "{{origin}}/user/balance",
            resultPath = if (genericDefault) "balance_infos[0].total_balance" else quotaQuery.resultPath,
        )
    }

val ProviderSetting.ApiKey.isMainlandSiliconFlow: Boolean
    get() = officialBase(setOf("api.siliconflow.cn", "api-st.siliconflow.cn"), setOf("", "/v1")) != null

/** Mainland /user/info retired on 2026-08-14. Do not apply this rule to the international .com service. */
internal val ProviderSetting.ApiKey.hasUnsupportedSiliconFlowQuota: Boolean
    get() {
        if (!isMainlandSiliconFlow) return false
        val base = baseUrl.toHttpUrl()
        val query = queryUrl() ?: return false
        return query.scheme == base.scheme && query.host == base.host && query.port == base.port &&
            query.username.isEmpty() && query.password.isEmpty() && query.query == null && query.fragment == null &&
            query.encodedPath in setOf("/credits", "/v1/credits", "/user/info", "/v1/user/info")
    }
