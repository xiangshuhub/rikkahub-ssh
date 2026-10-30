package me.rerere.ai.provider.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.AccountService
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.QuotaMetric
import me.rerere.common.http.await
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.uuid.Uuid

/** Fixed, read-only official endpoints. Cache failures too to avoid retry storms on 429/403. */
internal class SubscriptionQuotaQueries(
    client: OkHttpClient,
    private val credentials: AccountCredentialProvider,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val lock = Mutex()
    private data class Entry(val service: AccountService, val credential: OAuthCredential, val at: Long, val result: Result<ProviderQuota.AccountUsage>)
    private val cache = LinkedHashMap<Uuid, Entry>()

    suspend fun get(setting: ProviderSetting.Subscription): ProviderQuota.AccountUsage = lock.withLock {
        val stored = setting.credential ?: error("请先登录账号")
        if (setting.service == AccountService.META) {
            return@withLock parseSubscriptionQuota(setting.service,
                stored.quotaSnapshot ?: error("Meta 暂未返回额度快照；正常登录或凭据刷新后才可能更新"),
                stored.quotaSnapshotAt)
        }
        cache[setting.id]?.takeIf {
            it.service == setting.service && it.credential == stored &&
                nanoTime() - it.at < (if (it.result.isSuccess) 60L else 30L) * 1_000_000_000
        }?.let { return@withLock it.result.getOrThrow() }
        val result = try {
            Result.success(withContext(Dispatchers.IO) { withTimeout(30_000) { query(setting) } })
        } catch (e: TimeoutCancellationException) {
            Result.failure(IllegalStateException("额度查询超时，请稍后重试"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            Result.failure(IllegalStateException("额度接口返回了无法识别的数据"))
        } catch (e: Exception) {
            Result.failure(e)
        }
        cache[setting.id] = Entry(setting.service, stored, nanoTime(), result)
        while (cache.size > 64) cache.remove(cache.keys.first())
        result.getOrThrow()
    }

    private suspend fun query(setting: ProviderSetting.Subscription): ProviderQuota.AccountUsage {
        val copilot = setting.service == AccountService.COPILOT
        var credential = if (copilot) setting.credential!! else credentials.getCredential(setting, null)
        val url = when (setting.service) {
            AccountService.CLAUDE -> "https://api.anthropic.com/api/oauth/usage"
            AccountService.COPILOT -> "https://api.github.com/copilot_internal/user"
            AccountService.KIMI -> "https://api.kimi.com/coding/v1/usages"
            AccountService.OPENROUTER -> "https://openrouter.ai/api/v1/key"
            AccountService.META -> error("Meta quota must be read from the credential snapshot")
        }
        repeat(2) { attempt ->
            val token = if (copilot) credential.refreshToken ?: error("缺少 GitHub 授权，请重新登录") else credential.accessToken
            val request = Request.Builder().url(url).header("Accept", "application/json")
                .header("Authorization", "Bearer $token").apply {
                    if (setting.service == AccountService.CLAUDE) {
                        header("anthropic-beta", "oauth-2025-04-20")
                        header("User-Agent", "claude-cli/2.1.280")
                    }
                    if (copilot) {
                        header("User-Agent", "GitHubCopilotChat/0.35.0")
                        header("Editor-Version", "vscode/1.107.0")
                        header("Editor-Plugin-Version", "copilot-chat/0.35.0")
                        header("Copilot-Integration-Id", "vscode-chat")
                        header("X-GitHub-Api-Version", "2026-06-01")
                    }
                }.build()
            val retry = client.newCall(request).await().use { response ->
                if (response.code == 401 && attempt == 0 && !copilot && setting.service != AccountService.OPENROUTER) {
                    true
                } else {
                    check(response.isSuccessful) {
                        when (response.code) {
                            401 -> "额度授权失效，请重新登录"
                            403 -> "当前账号或授权无权查询额度"
                            429 -> "额度接口限流，请稍后刷新"
                            else -> "额度暂不可查询（HTTP ${response.code}）"
                        }
                    }
                    return parseSubscriptionQuota(setting.service,
                        Json.parseToJsonElement(response.body.string()) as? JsonObject
                            ?: error("额度接口返回了无法识别的数据"), System.currentTimeMillis())
                }
            }
            if (retry) credential = credentials.getCredential(setting, credential.accessToken)
        }
        error("额度暂不可查询")
    }
}

private fun JsonObject.obj(key: String) = get(key) as? JsonObject
private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
private fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true

/** Percent values are already on a 0–100 scale, including 0.5 and 1.0. Never rescale heuristically. */
internal fun parseSubscriptionQuota(service: AccountService, data: JsonObject, checkedAt: Long): ProviderQuota.AccountUsage {
    val rows = mutableListOf<QuotaMetric>()
    var note: String? = null
    when (service) {
        AccountService.CLAUDE -> {
            for ((key, label) in listOf("five_hour" to "5 小时", "seven_day" to "每周",
                "seven_day_opus" to "Opus 每周", "seven_day_sonnet" to "Sonnet 每周")) {
                val window = data.obj(key) ?: continue
                val percent = window.number("utilization")?.takeIf { it >= 0 } ?: continue
                rows += QuotaMetric(label, usedPercent = percent, resetsAt = window.text("resets_at"))
            }
            note = "订阅窗口用量，不代表 Token 数或费用。"
        }
        AccountService.COPILOT -> {
            val snapshots = data.obj("quota_snapshots")
            for ((key, label) in listOf("premium_interactions" to "Premium requests", "chat" to "Chat", "completions" to "Completions")) {
                val window = snapshots?.obj(key) ?: continue
                rows += countMetric(label, window, "entitlement", "remaining", data.text("quota_reset_date"), window.flag("unlimited"))
            }
            note = "GitHub 账号配额；不同请求可能按不同倍率消耗。"
        }
        AccountService.KIMI -> {
            data.obj("usage")?.let { rows += countMetric("总体配额", it, "limit", "remaining", it.text("resetTime")) }
            (data["limits"] as? JsonArray)?.forEachIndexed { index, element ->
                val item = element as? JsonObject ?: return@forEachIndexed
                val detail = item.obj("detail") ?: return@forEachIndexed
                val window = item.obj("window")
                val duration = window?.text("duration")
                val unit = when (window?.text("timeUnit")) {
                    "TIME_UNIT_MINUTE" -> "分钟"
                    "TIME_UNIT_HOUR" -> "小时"
                    "TIME_UNIT_DAY" -> "天"
                    "TIME_UNIT_SECOND" -> "秒"
                    else -> null
                }
                rows += countMetric(if (duration != null && unit != null) "$duration $unit" else "窗口 ${index + 1}",
                    detail, "limit", "remaining", detail.text("resetTime"))
            }
        }
        AccountService.META -> {
            for ((key, label) in listOf("window" to "当前窗口", "weekly" to "每周")) {
                val window = data.obj(key) ?: continue
                val percent = window.number("used_percent")?.takeIf { it >= 0 } ?: continue
                rows += QuotaMetric(label, usedPercent = percent, resetsAt = window.text("resets_at"))
            }
            note = "登录 / 刷新凭据时的额度快照，不是实时查询；手动刷新不会签发新 Key。"
        }
        AccountService.OPENROUTER -> {
            val key = data.obj("data") ?: error("OpenRouter 未返回 Key 预算数据")
            val limit = key.number("limit")
            rows += QuotaMetric("当前 Key 预算", remaining = key.number("limit_remaining"),
                limit = limit, unlimited = key.containsKey("limit") && key["limit"] == JsonNull, unit = "USD")
            key.number("usage")?.let { rows += QuotaMetric("累计使用", used = it, unit = "USD") }
            for ((field, label) in listOf("usage_daily" to "今日使用", "usage_weekly" to "本周使用", "usage_monthly" to "本月使用")) {
                key.number(field)?.let { rows += QuotaMetric(label, used = it, unit = "USD") }
            }
            (key["budgets"] as? JsonArray)?.forEachIndexed { index, value ->
                val budget = value as? JsonObject ?: return@forEachIndexed
                rows += QuotaMetric("预算 ${index + 1} (${budget.text("scope") ?: "unknown"})",
                    used = budget.number("spend_usd"), remaining = budget.number("remaining_usd"), limit = budget.number("limit_usd"),
                    unit = "USD", resetsAt = budget.text("resets_at"))
            }
            note = "USD 预算与用量，不是账号余额；累计用量不等于当前预算周期的消费。Key 无上限不代表账号有无限余额。"
        }
    }
    val known = rows.filter { it.unlimited || it.usedPercent != null || it.used != null || it.remaining != null || it.limit != null }
    check(known.isNotEmpty()) { "服务未返回可识别的额度数据" }
    return ProviderQuota.AccountUsage(known, checkedAt, note)
}

private fun countMetric(label: String, data: JsonObject, limitKey: String, remainingKey: String, reset: String?, unlimited: Boolean = false): QuotaMetric {
    val limit = data.number(limitKey)?.takeIf { it >= 0 }
    val remaining = data.number(remainingKey)
    val used = data.number("used")?.takeIf { it >= 0 } ?: if (limit != null && remaining != null) (limit - remaining).coerceAtLeast(0.0) else null
    val percent = if (!unlimited && limit != null && limit > 0 && used != null) (used / limit * 100).takeIf { it.isFinite() } else null
    return QuotaMetric(label, percent, used, remaining, limit, unlimited, resetsAt = reset)
}
