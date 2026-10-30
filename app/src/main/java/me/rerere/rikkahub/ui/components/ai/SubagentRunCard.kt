package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.subagent.SubagentRun
import me.rerere.rikkahub.subagent.SubagentService
import me.rerere.rikkahub.subagent.SubagentStatus
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

@Composable
fun SubagentRunCard(run: SubagentRun) {
    val service = koinInject<SubagentService>()
    val scope = rememberCoroutineScope()
    var expanded by remember(run.id) { mutableStateOf(false) }
    var answer by remember(run.id, run.questionKey) { mutableStateOf("") }
    var error by remember(run.id) { mutableStateOf<String?>(null) }
    var notice by remember(run.id) { mutableStateOf<String?>(null) }
    var trace by remember(run.id) { mutableStateOf<String?>(null) }
    var traceOffset by remember(run.id) { mutableIntStateOf(0) }
    var busy by remember(run.id) { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null; notice = null
            try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    CardGroup {
        item(onClick = { expanded = !expanded }, headlineContent = { Text("${run.agent} · ${run.status}") },
            supportingContent = { Text("${run.modelName} · 深度 ${run.depth} · ${run.steps} 步\n${run.inputTokens}/${run.outputTokens} tokens（报告或保守预留）" +
                run.estimatedCostUsd?.let { " · 估算 USD %.4f".format(it) }.orEmpty() + "\n${run.task.take(120)}") })
        error?.let { item(headlineContent = { Text(it, color = MaterialTheme.colorScheme.error) }) }
        notice?.let { item(headlineContent = { Text(it, color = MaterialTheme.colorScheme.primary) }) }
        if (run.status == SubagentStatus.WAITING_INPUT) formItem(headlineContent = { Text("子代理询问父助手 / 用户") }) {
            SelectionContainer { Text(run.question.orEmpty()) }
            OutlinedTextField(answer, { answer = it }, modifier = Modifier.fillMaxWidth(), label = { Text("回复问题（不是工具审批）") })
            TextButton(enabled = !busy && answer.isNotBlank(), onClick = { action { service.reply(run, answer); answer = "" } }) { Text("回复并继续") }
        }
        if (run.status.active) item(onClick = { action { service.cancel(run) } }, headlineContent = { Text(if (busy) "处理中…" else "取消此任务及其后代") })
        if (!run.status.active && run.status !in setOf(SubagentStatus.COMPLETED, SubagentStatus.UNCERTAIN)) {
            item(onClick = { action { service.resume(run) } }, headlineContent = { Text("从安全检查点继续") },
                supportingContent = { Text("保留已有上下文与预算；最多 3 次运行。预算耗尽时需先调整配置。") })
        }
        if (expanded) {
            formItem {
                SelectionContainer { Text("任务：${run.id}\n父任务：${run.parentRunId ?: "主对话"}\n父对话：${run.owner.conversationId}\n${run.task}\n\n${run.error.orEmpty()}\n${run.output}") }
            }
            item(onClick = { action { traceOffset = 0; trace = service.trace(run) } }, headlineContent = { Text("执行日志 / 工具轨迹") })
            run.worktree?.let { tree ->
                item(headlineContent = { Text("隔离工作树") }, supportingContent = { SelectionContainer { Text("${tree.path}\n补丁：${tree.patch ?: "尚未捕获"}") } })
                if (!run.status.active) {
                    item(onClick = { action { service.capturePatch(run) } }, headlineContent = { Text("捕获 / 更新补丁（不提交）") })
                    if (tree.patch != null) item(onClick = { action { notice = service.applyPatch(run) } }, headlineContent = { Text("检查并应用补丁到父仓库") })
                }
            }
        }
    }
    trace?.let { content ->
        val paging = Regex("next_offset=(\\d+); total_bytes=(\\d+)").findAll(content).lastOrNull()
        val hasMore = paging?.let { it.groupValues[1].toLong() < it.groupValues[2].toLong() } == true
        AlertDialog(onDismissRequest = { trace = null }, title = { Text("任务执行日志 · offset $traceOffset") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("日志含任务与工具数据，请勿随意公开。单任务日志上限 8 MiB；按 64 KiB 分页。")
                SelectionContainer { Text(content, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy && hasMore, onClick = { action { traceOffset += 65536; trace = service.trace(run, traceOffset) } }) { Text("下一页") }
        }, dismissButton = {
            TextButton(enabled = !busy && traceOffset > 0, onClick = { action { traceOffset = (traceOffset - 65536).coerceAtLeast(0); trace = service.trace(run, traceOffset) } }) { Text("上一页") }
        })
    }
}
