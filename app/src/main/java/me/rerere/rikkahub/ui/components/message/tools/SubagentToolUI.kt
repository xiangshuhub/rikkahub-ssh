package me.rerere.rikkahub.ui.components.message.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.*
import me.rerere.rikkahub.subagent.SubagentRun
import me.rerere.rikkahub.subagent.SubagentService
import me.rerere.rikkahub.ui.components.ai.SubagentRunCard
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

internal class SubagentToolUI(override val toolName: String) : ToolUIRenderer {
    companion object {
        val all = listOf("subagent_dispatch", "subagent_workflow", "subagent_status", "subagent_wait", "subagent_reply", "subagent_resume", "subagent_cancel", "subagent_capture_patch", "subagent_apply_patch")
            .map { SubagentToolUI(it) }
    }
    @Composable override fun title(context: ToolUIContext) = "子代理 · " + toolName.removePrefix("subagent_")
    override fun hasSummary(context: ToolUIContext) = context.conversationId != null
    @Composable private fun records(context: ToolUIContext): List<SubagentRun> {
        val service = koinInject<SubagentService>()
        val runs by service.runs.collectAsStateWithLifecycle()
        val owned = runs.filter { it.owner.conversationId == context.conversationId && it.owner.assistantId == context.assistant?.id }
        val ids = mutableSetOf<Uuid>()
        fun visit(value: JsonElement?) {
            when (value) {
                is JsonArray -> value.forEach(::visit)
                is JsonObject -> {
                    (value["id"] as? JsonPrimitive)?.contentOrNull?.let { runCatching { Uuid.parse(it) }.getOrNull() }?.let(ids::add)
                    listOf("runs", "stages").forEach { visit(value[it]) }
                }
                else -> Unit
            }
        }
        visit(context.content)
        (context.arguments as? JsonObject)?.get("ids")?.let { value -> (value as? JsonArray)?.forEach { id ->
            (id as? JsonPrimitive)?.contentOrNull?.let { runCatching { Uuid.parse(it) }.getOrNull() }?.let(ids::add)
        } }
        owned.filter { it.originToolCallId == context.tool.toolCallId }.forEach { ids += it.id }
        repeat(3) { owned.filter { it.parentRunId in ids }.forEach { ids += it.id } }
        return owned.filter { it.id in ids }.sortedWith(compareByDescending<SubagentRun> { it.status.active }.thenBy { it.createdAt })
    }
    @Composable private fun OperationMessages(context: ToolUIContext) {
        (context.content as? JsonArray)?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
            if (status in setOf("FAILED_OR_UNCERTAIN", "REJECTED", "APPLIED")) {
                val message = (obj["error"] as? JsonPrimitive)?.contentOrNull ?: (obj["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                Text("$status: $message", color = if (status == "APPLIED") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
        }
    }
    @Composable override fun Summary(context: ToolUIContext) {
        val records = records(context)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OperationMessages(context)
            if (records.isEmpty()) Text(if (context.loading) "正在准备子任务…" else "任务记录不可用，请查看工具原始结果。")
            records.take(4).forEach { run -> key(run.id) { SubagentRunCard(run) } }
            if (records.size > 4) Text("另有 ${records.size - 4} 项；点击工具详情查看全部。")
        }
    }
    @Composable override fun Preview(context: ToolUIContext, onDismissRequest: () -> Unit) {
        val records = records(context)
        if (records.isEmpty()) {
            DefaultToolPreview(context)
        } else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OperationMessages(context)
            records.forEach { run -> key(run.id) { SubagentRunCard(run) } }
        }
    }
}
