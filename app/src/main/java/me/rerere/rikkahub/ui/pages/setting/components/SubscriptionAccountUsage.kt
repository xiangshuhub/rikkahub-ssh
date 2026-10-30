package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import me.rerere.ai.provider.AccountService
import me.rerere.ai.provider.ProviderQuota
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.rikkahub.ui.components.ai.rememberProviderQuota
import me.rerere.rikkahub.ui.components.ai.summary
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun SubscriptionAccountUsage(provider: ProviderSetting.Subscription) {
    var refresh by remember(provider.id) { mutableIntStateOf(0) }
    val result = rememberProviderQuota(provider, refresh)
    val usage = result?.getOrNull() as? ProviderQuota.AccountUsage
    val meta = provider.service == AccountService.META
    CardGroup(title = { Text(if (provider.service == AccountService.OPENROUTER) "Key 预算与费用" else "订阅额度") }) {
        item(
            onClick = { if (result != null) refresh++ },
            leadingContent = { CardGroupIcon(HugeIcons.Refresh03) },
            headlineContent = { Text(if (meta) "重新读取额度快照" else "刷新额度") },
            supportingContent = { Text(if (meta) "不会额外签发 Key；快照仅在登录或正常刷新凭据时更新。"
                else "成功结果缓存 1 分钟；失败后等待 30 秒再重试。") },
        )
        when {
            result == null -> item(
                headlineContent = { Text("正在查询额度…") },
                leadingContent = { ContainedLoadingIndicator() },
            )
            usage == null -> item(
                headlineContent = { Text("额度暂不可查询") },
                supportingContent = { Text(result.exceptionOrNull()?.message ?: "未返回可识别的额度数据") },
            )
            else -> {
                usage.metrics.forEach { metric ->
                    formItem(
                        headlineContent = { Text(metric.label) },
                        supportingContent = { Text(metric.summary() + (metric.resetsAt?.let { "\n重置：$it" } ?: "")) },
                    ) {
                        metric.usedPercent?.takeUnless { metric.unlimited }?.let { percent ->
                            LinearWavyProgressIndicator(
                                progress = { (percent / 100).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                item(
                    headlineContent = { Text(if (usage.checkedAt > 0) "数据时间：" + DateTimeFormatter
                        .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(usage.checkedAt)) else "数据时间未知") },
                    supportingContent = usage.note?.let { note -> { Text(note) } },
                )
            }
        }
    }
}
