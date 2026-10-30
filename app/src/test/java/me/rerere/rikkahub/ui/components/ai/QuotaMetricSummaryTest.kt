package me.rerere.rikkahub.ui.components.ai

import me.rerere.ai.provider.QuotaMetric
import org.junit.Assert.*
import org.junit.Test

class QuotaMetricSummaryTest {
    @Test
    fun `unknown values are not displayed as zero or unlimited`() {
        assertEquals("暂不可查询", QuotaMetric("unknown").summary())
        assertEquals("剩余 0", QuotaMetric("zero", remaining = 0.0).summary())
        assertEquals("0.5% 已用", QuotaMetric("small", usedPercent = 0.5).summary())
    }

    @Test
    fun `unlimited subscription and uncapped monetary budget have different wording`() {
        assertEquals("无限额度", QuotaMetric("chat", unlimited = true).summary())
        val budget = QuotaMetric("budget", unlimited = true, unit = "USD").summary()
        assertTrue(budget.contains("Key 未设置限额"))
        assertFalse(budget.contains("无限"))
        assertEquals("已用 12.5 USD", QuotaMetric("usage", used = 12.5, unit = "USD").summary())
    }
}
