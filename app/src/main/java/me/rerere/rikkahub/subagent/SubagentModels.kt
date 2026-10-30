package me.rerere.rikkahub.subagent

import kotlinx.serialization.Serializable
import me.rerere.ai.core.Tool
import kotlin.uuid.Uuid
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class SubagentProfile(
    val name: String,
    val description: String = "",
    val systemPrompt: String = "Complete only the assigned task and return a concise, evidence-based result.",
    val modelId: Uuid? = null,
    val readOnly: Boolean = true,
    /** Empty means the profile ceiling, not authority to add tools the parent lacks. */
    val allowedTools: Set<String> = emptySet(),
    val enabled: Boolean = true,
    val canDelegate: Boolean = false,
    val isolateWorktree: Boolean = false,
    val inputUsdPerMillion: Double? = null,
    val outputUsdPerMillion: Double? = null,
)

@Serializable
data class SubagentSettings(
    val enabled: Boolean = false,
    val autoApproveDispatch: Boolean = true,
    val trustedTools: Boolean = true,
    val autoWake: Boolean = true,
    val autoResume: Boolean = true,
    val maxDepth: Int = 2,
    val maxTotalTokens: Int = 100_000,
    val maxCostUsd: Double? = null,
    val maxConcurrent: Int = 3,
    val maxSteps: Int = 12,
    val timeoutSeconds: Int = 300,
    val maxOutputTokens: Int = 2048,
    val profiles: List<SubagentProfile> = listOf(
        SubagentProfile("scout", "查找资料与代码，返回精简线索", "Inspect the assigned scope. Return relevant evidence, paths and uncertainties. Do not modify anything."),
        SubagentProfile("reviewer", "独立检查方案或代码", "Review the assigned material. Report concrete issues with evidence, severity and suggested fixes. Do not modify anything."),
        SubagentProfile("coordinator", "拆分任务并协调子代理", "Delegate bounded independent tasks, answer child questions and collect results before finishing. Never expand the assigned scope.", readOnly = false, canDelegate = true),
        SubagentProfile("worker", "执行有明确边界的任务", "Complete the assigned task using only available authorized tools. Read relevant project instructions before editing. Validate the result. Report changes, checks and remaining risks. Do not commit, push, remove worktrees or install dependencies unless the assigned task requires it. Do not expand scope.", readOnly = false),
    ),
)

@Serializable
enum class SubagentStatus {
    QUEUED, RUNNING, WAITING_INPUT, CANCELLING, COMPLETED, BLOCKED, STEP_LIMIT, BUDGET_LIMIT, TIMED_OUT, CANCELLED, FAILED, PROCESS_LOST, UNCERTAIN;
    val active: Boolean get() = this == QUEUED || this == RUNNING || this == WAITING_INPUT || this == CANCELLING
}

@Serializable
data class SubagentOwner(val assistantId: Uuid, val conversationId: Uuid)

@Serializable
data class SubagentRun(
    val id: Uuid = Uuid.random(),
    val owner: SubagentOwner,
    val agent: String,
    val task: String,
    val modelId: Uuid,
    val modelName: String,
    val background: Boolean,
    val originToolCallId: String? = null,
    val budgetGroupId: Uuid? = null,
    val parentRunId: Uuid? = null,
    val depth: Int = 1,
    val attempts: Int = 1,
    val parentSuppressed: Boolean = false,
    val startEpoch: Long = 0,
    val cancelEpoch: Long = 0,
    val status: SubagentStatus = SubagentStatus.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val output: String = "",
    val error: String? = null,
    val steps: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val estimatedCostUsd: Double? = null,
    val question: String? = null,
    val questionKey: String? = null,
    val answer: String? = null,
    val eventRevision: Int = 0,
    val deliveredRevision: Int = 0,
    val parentWakeAt: Long? = null,
    val parentWakeHistory: List<Long> = emptyList(),
    val worktree: SubagentWorktree? = null,
)

@Serializable
data class SubagentWorktree(val repository: String, val path: String, val baseCommit: String, val patch: String? = null, val patchHash: String? = null)

internal class SubagentBudgetExceeded : RuntimeException("Subagent usage budget reached")
internal class SubagentInfrastructureException(message: String) : IllegalStateException(message)
internal class SubagentCapacityException(message: String) : IllegalStateException(message)

data class SubagentOutcome(val status: SubagentStatus, val output: String, val error: String? = null)

internal const val SUBAGENT_OUTPUT_LIMIT = 32_768
internal const val SUBAGENT_TASK_LIMIT = 8_192

/** Explicit allowlist: arbitrary shell/JS/MCP tools cannot be proved read-only. */
private val readOnlyTools = setOf("workspace_read_file", "workspace_list_files", "workspace_search", "search_web", "scrape_web", "get_time_info", "recent_chats", "conversation_search", "conversation_read", "use_skill")

internal fun childTools(parent: List<Tool>, profile: SubagentProfile, requested: Set<String>?): List<Tool> {
    val ceiling = parent.filter {
        !it.name.startsWith("subagent") && !it.name.startsWith("contact_supervisor") && it.name != "read_dependency" &&
            (!profile.readOnly || it.name in readOnlyTools) &&
            (profile.allowedTools.isEmpty() || it.name in profile.allowedTools)
    }
    require(requested == null || requested.all { name -> ceiling.any { it.name == name } }) {
        "Requested tools exceed this profile's parent-authorized capability ceiling"
    }
    return ceiling.filter { requested == null || it.name in requested }
}

internal fun boundedChildTools(tools: List<Tool>, limit: Int = 64): List<Tool> {
    val calls = AtomicInteger()
    return tools.map { tool -> tool.copy(execute = { args ->
        check(calls.incrementAndGet() <= limit) { "Child tool budget exhausted; stop and summarize the partial result" }
        tool.execute(args)
    }) }
}

internal fun SubagentSettings.validate() {
    require(maxDepth in 1..3) { "Subagent depth must be 1–3" }
    require(maxTotalTokens in 1024..1_000_000) { "Token budget must be 1024–1000000" }
    require(maxCostUsd == null || maxCostUsd.isFinite() && maxCostUsd > 0) { "Invalid cost budget" }
    require(profiles.all { profile -> listOfNotNull(profile.inputUsdPerMillion, profile.outputUsdPerMillion).all { it.isFinite() && it in 0.0..1_000_000.0 } }) { "Invalid model price" }
    require(maxConcurrent in 1..4) { "Subagent concurrency must be 1–4" }
    require(maxSteps in 1..24) { "Subagent step limit must be 1–24" }
    require(timeoutSeconds in 30..1800) { "Subagent timeout must be 30–1800 seconds" }
    require(maxOutputTokens in 256..8192) { "Subagent output tokens must be 256–8192" }
    require(profiles.none { it.isolateWorktree && it.canDelegate }) { "Isolated roles must be leaves; dispatch parallel isolated writers from the main conversation" }
    require(profiles.size <= 16) { "At most 16 subagent profiles" }
    require(profiles.all {
        it.name.matches(Regex("[a-zA-Z0-9_-]{1,32}")) && it.systemPrompt.length <= 8192 && it.description.length <= 512 &&
            it.allowedTools.size <= 128 && it.allowedTools.all { name -> name.isNotBlank() && name.length <= 128 }
    }) { "Invalid profile name, prompt or tool allowlist" }
    require(profiles.map { it.name.lowercase() }.distinct().size == profiles.size) { "Profile names must be unique" }
}
