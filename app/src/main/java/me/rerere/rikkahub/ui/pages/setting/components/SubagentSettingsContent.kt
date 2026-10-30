package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.subagent.SubagentProfile
import me.rerere.rikkahub.subagent.SubagentService
import me.rerere.rikkahub.subagent.SubagentSettings
import me.rerere.rikkahub.subagent.validate
import me.rerere.rikkahub.subagent.importLegacySubagents
import me.rerere.rikkahub.subagent.legacySubagentConfigurations
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceButtons
import me.rerere.rikkahub.ui.components.ui.ConnectedChoiceLabel
import me.rerere.rikkahub.ui.components.ui.switchItem
import org.koin.compose.koinInject

@Composable
internal fun SubagentSettingsContent(
    config: SubagentSettings,
    providers: List<ProviderSetting>,
    assistants: List<Assistant>,
    legacyAssistants: List<Assistant>,
    onUpdate: (SubagentSettings) -> Unit,
) {
    val service = koinInject<SubagentService>()
    val runs by service.runs.collectAsStateWithLifecycle()
    val storeError by service.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var actionError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<SubagentProfile?>(null) }
    var editIndex by remember { mutableStateOf(-1) }
    var showLegacy by remember { mutableStateOf(false) }
    val legacy = legacySubagentConfigurations(legacyAssistants)
    fun update(value: SubagentSettings) {
        try {
            if (value.enabled) value.validate()
            actionError = null
            onUpdate(value)
        } catch (e: IllegalArgumentException) { actionError = e.message }
    }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            try { actionError = null; block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { actionError = e.message ?: "操作失败" }
        }
    }

    CardGroup(title = { Text("子代理（Pi 风格）") }) {
        switchItem(checked = config.enabled, onCheckedChange = { update(config.copy(enabled = it)) },
            headlineContent = { Text("全局启用子代理") },
            supportingContent = { Text("默认开启、免审批，对所有跟随全局的普通助手生效。关闭会取消活动子任务；不会凭空创建新任务，已有中断任务可按安全恢复开关继续。") })
        item(headlineContent = { Text("权限与后台边界") }, supportingContent = {
            Text("角色和参数全局共享，但子代理只继承当前父助手的工具、工作区与会话权限，不合并其他助手权限。管理助手不参与；只读、预算和不确定写入保护仍有效。")
        })
        actionError?.let { item(headlineContent = { Text(it, color = MaterialTheme.colorScheme.error) }) }
            switchItem(checked = config.autoApproveDispatch,
                onCheckedChange = { update(config.copy(autoApproveDispatch = it)) },
                headlineContent = { Text("自动批准子代理派发") },
                supportingContent = { Text("默认开启。模型可以在预算与权限范围内直接派发任务，不再弹派发确认。") })
            switchItem(checked = config.trustedTools, onCheckedChange = { update(config.copy(trustedTools = it)) },
                headlineContent = { Text("信任子代理工具执行（无需审批）") },
                supportingContent = { Text("在父助手的工具权限范围内直接执行；只读与白名单限制仍有效。业务问题通过父子问答解决，不伪装成审批。") })
            switchItem(checked = config.autoWake, onCheckedChange = { update(config.copy(autoWake = it)) },
                headlineContent = { Text("完成 / 提问后自动唤醒父助手") }, supportingContent = { Text("会额外调用主模型；每个对话每小时最多 4 次，自动汇总轮不能新建子任务或执行外部修改。") })
            switchItem(checked = config.autoResume, onCheckedChange = { update(config.copy(autoResume = it)) },
                headlineContent = { Text("启动后恢复安全检查点") }, supportingContent = { Text("最多 3 次运行；写入执行状态不确定时停止并报告。") })
            formItem(headlineContent = { Text("最大子代理深度") }) {
                ConnectedChoiceButtons(options = listOf(1, 2, 3), selected = config.maxDepth,
                    onSelect = { update(config.copy(maxDepth = it)) }) { value, selected -> ConnectedChoiceLabel("$value", selected) }
            }
            formItem(headlineContent = { Text("同轮子任务树共享 Token 预算") }) {
                ConnectedChoiceButtons(options = listOf(32_000, 100_000, 250_000), selected = config.maxTotalTokens,
                    onSelect = { update(config.copy(maxTotalTokens = it)) }) { value, selected -> ConnectedChoiceLabel("${value / 1000}k", selected) }
            }
            formItem(headlineContent = { Text("估算费用预算（USD，可留空）") }) {
                var budget by remember(config.maxCostUsd) { mutableStateOf(config.maxCostUsd?.toString().orEmpty()) }
                OutlinedTextField(budget, { budget = it }, modifier = Modifier.fillMaxWidth(), supportingText = { Text("启用后，参与角色必须固定模型并填写输入/输出单价。预留与报告值不是账单；当前请求仍可能导致超额。") })
                TextButton(onClick = {
                    val value = budget.toDoubleOrNull()
                    if (budget.isBlank() || value != null && value.isFinite() && value > 0) update(config.copy(maxCostUsd = value))
                    else actionError = "费用预算必须是正数或留空"
                }) { Text("保存预算") }
            }
            formItem(headlineContent = { Text("每个对话的并发上限（全局最多 8）") }) {
                ConnectedChoiceButtons(options = listOf(1, 2, 3, 4), selected = config.maxConcurrent,
                    onSelect = { update(config.copy(maxConcurrent = it)) }) { value, selected -> ConnectedChoiceLabel("$value", selected) }
            }
            formItem(headlineContent = { Text("每个子任务最多模型请求次数") }) {
                ConnectedChoiceButtons(options = listOf(6, 12, 24), selected = config.maxSteps,
                    onSelect = { update(config.copy(maxSteps = it)) }) { value, selected -> ConnectedChoiceLabel("$value", selected) }
            }
            formItem(headlineContent = { Text("超时（秒）") }) {
                ConnectedChoiceButtons(options = listOf(120, 300, 900), selected = config.timeoutSeconds,
                    onSelect = { update(config.copy(timeoutSeconds = it)) }) { value, selected -> ConnectedChoiceLabel("$value", selected) }
            }
            formItem(headlineContent = { Text("单次模型输出 Token 上限") }) {
                ConnectedChoiceButtons(options = listOf(1024, 2048, 4096, 8192), selected = config.maxOutputTokens,
                    onSelect = { update(config.copy(maxOutputTokens = it)) }) { value, selected -> ConnectedChoiceLabel("$value", selected) }
            }
    }

    if (legacy.isNotEmpty()) CardGroup {
        item(onClick = { showLegacy = true }, headlineContent = { Text("导入旧版助手子代理配置") },
            supportingContent = { Text("旧配置仍保留但不再单独生效。可导入一份角色与参数；保持当前全局总开关和信任模式，不会自动混合不同助手配置。") })
    }
    if (showLegacy) AlertDialog(
        onDismissRequest = { showLegacy = false }, title = { Text("选择旧配置（替换全局角色与参数）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                legacy.forEach { source ->
                    TextButton(onClick = {
                        try { update(importLegacySubagents(config, source.subagents)); showLegacy = false }
                        catch (e: IllegalArgumentException) { actionError = e.message; showLegacy = false }
                    }) { Text("${source.name.ifBlank { source.id.toString() }} · ${source.subagents.profiles.size} 个角色") }
                }
            }
        }, confirmButton = { TextButton(onClick = { showLegacy = false }) { Text("取消") } },
    )

        config.profiles.forEachIndexed { index, profile ->
            val modelState = rememberModelListState(modelId = profile.modelId, providers = providers.filter { it.enabled }, type = ModelType.CHAT)
            ModelListSheet(state = modelState, onSelect = { model ->
                update(config.copy(profiles = config.profiles.mapIndexed { i, p -> if (i == index) p.copy(modelId = model.id, inputUsdPerMillion = null, outputUsdPerMillion = null) else p }))
            })
            CardGroup(title = { Text(profile.name) }) {
                switchItem(checked = profile.enabled, onCheckedChange = { enabled ->
                    update(config.copy(profiles = config.profiles.mapIndexed { i, p -> if (i == index) p.copy(enabled = enabled) else p }))
                }, headlineContent = { Text(profile.description.ifBlank { profile.name }) },
                    supportingContent = { Text(if (profile.readOnly) "只读工具权限" else "可执行父级允许的工具；信任模式不弹审批") })
                formItem(headlineContent = { Text(if (profile.modelId == null) "模型：继承父助手" else "子代理模型") }) {
                    ModelField(model = modelState.currentModel, onClick = { modelState.open() })
                    if (profile.modelId != null) TextButton(onClick = {
                        update(config.copy(profiles = config.profiles.mapIndexed { i, p -> if (i == index) p.copy(modelId = null, inputUsdPerMillion = null, outputUsdPerMillion = null) else p }))
                    }) { Text("恢复继承") }
                }
                item(onClick = { editIndex = index; editing = profile }, headlineContent = { Text("编辑角色、提示词与工具限制") })
            }
        }
        CardGroup {
            if (config.profiles.size < 16) item(onClick = {
                editIndex = -1
                val name = (1..100).map { "custom_$it" }.first { name -> config.profiles.none { it.name == name } }
                editing = SubagentProfile(name)
            }, headlineContent = { Text("添加角色") })
        }

    editing?.let { profile ->
        ProfileEditor(profile, onDismiss = { editing = null }, onDelete = if (editIndex >= 0) ({
            update(config.copy(profiles = config.profiles.filterIndexed { i, _ -> i != editIndex })); editing = null
        }) else null, onSave = { changed ->
            val profiles = if (editIndex < 0) config.profiles + changed else config.profiles.mapIndexed { i, p -> if (i == editIndex) changed else p }
            val next = config.copy(profiles = profiles)
            if (next.enabled) next.validate() else SubagentSettings(profiles = listOf(changed)).validate()
            require(profiles.count { it.name.equals(changed.name, true) } == 1) { "角色名称必须唯一" }
            update(next)
            editing = null
        })
    }

    val owned = runs.sortedWith(compareByDescending<me.rerere.rikkahub.subagent.SubagentRun> { it.status.active }.thenByDescending { it.createdAt })
    CardGroup(title = { Text("子代理任务 · ${owned.count { it.status.active }} 个活动任务") }) {
        (storeError ?: actionError)?.let { message -> item(headlineContent = { Text(message, color = MaterialTheme.colorScheme.error) }) }
        item(headlineContent = { Text("任务结果与取消") }, supportingContent = {
            Text("聊天中也可查看任务树、日志、问答和继续执行。停止主对话会取消整棵子任务树。清理记录会删除私有日志与检查点，但不会删除工作树或撤销文件修改。")
        })
        if (owned.any { it.status.active }) item(onClick = { action {
            owned.filter { it.status.active }.map { it.owner.conversationId }.distinct().forEach { service.cancelConversation(it) }
        } }, headlineContent = { Text("取消全部活动子任务") })
        if (owned.any { !it.status.active }) item(onClick = { action {
            owned.map { it.owner.assistantId }.distinct().forEach { service.clearFinished(it) }
        } }, headlineContent = { Text("清除已结束任务记录") })
        if (owned.isEmpty()) item(headlineContent = { Text("暂无任务") })
    }
    val names = assistants.associate { it.id to it.name }
    owned.forEach { run -> androidx.compose.runtime.key(run.id) {
        Text("父助手：${names[run.owner.assistantId]?.ifBlank { "默认助手" } ?: "历史助手"}", style = MaterialTheme.typography.labelLarge)
        me.rerere.rikkahub.ui.components.ai.SubagentRunCard(run)
    } }
}

@Composable
private fun ProfileEditor(profile: SubagentProfile, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (SubagentProfile) -> Unit) {
    var name by remember { mutableStateOf(profile.name) }
    var description by remember { mutableStateOf(profile.description) }
    var prompt by remember { mutableStateOf(profile.systemPrompt) }
    var tools by remember { mutableStateOf(profile.allowedTools.joinToString(", ")) }
    var readOnly by remember { mutableStateOf(profile.readOnly) }
    var canDelegate by remember { mutableStateOf(profile.canDelegate) }
    var isolated by remember { mutableStateOf(profile.isolateWorktree) }
    var inputPrice by remember { mutableStateOf(profile.inputUsdPerMillion?.toString().orEmpty()) }
    var outputPrice by remember { mutableStateOf(profile.outputUsdPerMillion?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("子代理角色") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("名称（字母、数字、下划线）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("用途") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(prompt, { prompt = it }, label = { Text("角色系统提示词") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            OutlinedTextField(tools, { tools = it }, label = { Text("工具名白名单，逗号分隔；空白沿用角色上限") }, modifier = Modifier.fillMaxWidth())
            CardGroup {
                switchItem(checked = readOnly, onCheckedChange = { readOnly = it }, headlineContent = { Text("只读角色（运行时工具限制）") })
                switchItem(checked = canDelegate, onCheckedChange = { canDelegate = it }, headlineContent = { Text("允许在深度和工具上限内继续委派") })
                switchItem(checked = isolated, onCheckedChange = { isolated = it }, headlineContent = { Text("使用独立 Git 工作树") },
                    supportingContent = { Text("需父级 Shell 和干净仓库；仅支持主对话直接派发，隔离角色不可继续委派。不是操作系统沙箱，不自动提交、删除或丢弃修改。") })
            }
            OutlinedTextField(inputPrice, { inputPrice = it }, label = { Text("每百万输入 tokens 单价（USD）") })
            OutlinedTextField(outputPrice, { outputPrice = it }, label = { Text("每百万输出 tokens 单价（USD）") })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            onDelete?.let { TextButton(onClick = it) { Text("删除角色") } }
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            require(inputPrice.isBlank() || inputPrice.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true) { "输入单价无效" }
            require(outputPrice.isBlank() || outputPrice.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true) { "输出单价无效" }
            onSave(profile.copy(name = name.trim(), description = description.take(512), systemPrompt = prompt,
                readOnly = readOnly, canDelegate = canDelegate, isolateWorktree = isolated,
                inputUsdPerMillion = inputPrice.toDoubleOrNull(), outputUsdPerMillion = outputPrice.toDoubleOrNull(),
                allowedTools = tools.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()))
        }
        catch (e: IllegalArgumentException) { error = e.message }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
