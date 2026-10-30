package me.rerere.rikkahub.subagent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import kotlin.math.floor

/** Conservative reservations, reconciled when the provider reports usage. Not an invoice calculator. */
internal class SubagentBudget(
    private val config: SubagentSettings,
    private val profile: SubagentProfile,
    input: Int = 0,
    output: Int = 0,
) {
    var inputTokens = input; private set
    var outputTokens = output; private set
    private var beforeInput = input
    private var beforeOutput = output
    val cost: Double? get() = if (profile.inputUsdPerMillion != null && profile.outputUsdPerMillion != null)
        (inputTokens * profile.inputUsdPerMillion + outputTokens * profile.outputUsdPerMillion) / 1_000_000 else null

    init {
        require(config.maxCostUsd == null || profile.inputUsdPerMillion != null && profile.outputUsdPerMillion != null) {
            "A USD budget requires this profile's input and output prices"
        }
    }

    fun reserve(messages: List<UIMessage>, toolSchemaBytes: Long = 0): Int {
        // UTF-8 bytes plus framing overhead deliberately overestimate common tokenizers.
        val prompt = Json.encodeToString(messages).toByteArray().size.toLong() + toolSchemaBytes.coerceAtLeast(0) + 1024
        var output = minOf(config.maxOutputTokens.toLong(), config.maxTotalTokens.toLong() - inputTokens - outputTokens - prompt)
        config.maxCostUsd?.let { limit ->
            val available = limit - (cost ?: 0.0) - prompt * profile.inputUsdPerMillion!! / 1_000_000
            if (available < 0) throw SubagentBudgetExceeded()
            if (profile.outputUsdPerMillion!! > 0) output = minOf(output, floor(available * 1_000_000 / profile.outputUsdPerMillion).toLong())
        }
        if (output < 256 || prompt > Int.MAX_VALUE) throw SubagentBudgetExceeded()
        beforeInput = inputTokens; beforeOutput = outputTokens
        inputTokens += prompt.toInt(); outputTokens += output.toInt()
        return output.toInt()
    }

    fun settle(usage: TokenUsage?) {
        if (usage == null) return // Unknown/zero/invalid components retain their conservative reservation.
        if (usage.promptTokens > 0) inputTokens = (beforeInput.toLong() + usage.promptTokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (usage.completionTokens > 0) outputTokens = (beforeOutput.toLong() + usage.completionTokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun check() {
        if (inputTokens.toLong() + outputTokens > config.maxTotalTokens ||
            config.maxCostUsd?.let { (cost ?: Double.POSITIVE_INFINITY) > it } == true) throw SubagentBudgetExceeded()
    }
}
