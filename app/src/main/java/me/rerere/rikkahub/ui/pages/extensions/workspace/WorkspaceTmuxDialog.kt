package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.ssh.TmuxClient
import me.rerere.workspace.ssh.TmuxSession
import me.rerere.workspace.ssh.TmuxWindow
import org.koin.compose.koinInject

/** No automatic installation, command replay or remote session destruction on dismissal. */
@Composable
internal fun WorkspaceTmuxDialog(
    workspace: WorkspaceEntity,
    cwd: String,
    onAttach: (command: String, label: String) -> Unit,
    onPlain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val repository = koinInject<WorkspaceRepository>()
    val client = remember(workspace.id) {
        TmuxClient { command -> repository.executeCommand(workspace.id, command, timeoutMillis = 20_000) }
    }
    val scope = rememberCoroutineScope()
    var choices by remember { mutableStateOf(emptyList<Pair<TmuxSession, TmuxWindow>>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("rikka-" + java.util.UUID.randomUUID().toString().take(8)) }
    var killTarget by remember { mutableStateOf<TmuxSession?>(null) }
    // Failed creates may have succeeded remotely; require an explicit rescan before another create.
    var createUncertain by remember { mutableStateOf(false) }

    suspend fun refresh() {
        choices = client.sessions().flatMap { session -> client.windows(session).map { session to it } }
    }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "tmux 操作失败，请刷新核验" }
            finally { busy = false }
        }
    }
    LaunchedEffect(client) {
        busy = true
        try { refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message }
        finally { busy = false }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("${workspace.name} · tmux") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text("连接现有窗口，或明确新建会话。关闭本地标签仅断开连接，不结束远端 tmux。")
                if (busy) Text("处理中…")
                error?.let { Text(it) }
                if (!busy && error == null && choices.isEmpty()) Text("没有 tmux 会话，可在下方新建。")
                choices.forEach { (session, window) ->
                    TextButton(enabled = !busy, onClick = {
                        onAttach(client.attachCommand(session, window), "${session.name}/${window.name}")
                    }) { Text("连接 ${session.name} / ${window.name} (${session.id}:${window.id})") }
                }
                choices.map { it.first }.distinctBy { it.id }.forEach { session ->
                    TextButton(enabled = !busy, onClick = { killTarget = session }) { Text("结束会话 ${session.name}…") }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("新会话名称") }, enabled = !busy)
                TextButton(enabled = !busy && !createUncertain && TmuxClient.validName(name), onClick = {
                    val requestedName = name
                    work {
                        val layout = repository.layout(workspace.id) ?: error("工作区不存在")
                        val directory = if (cwd.startsWith('/')) cwd else layout.absolutePath(WorkspaceStorageArea.FILES, cwd)
                        createUncertain = true
                        val session = client.create(requestedName, directory)
                        val window = client.windows(session).single()
                        onAttach(client.attachCommand(session, window), "${session.name}/${window.name}")
                    }
                }) { Text("新建并连接") }
                TextButton(enabled = !busy, onClick = { work { refresh(); createUncertain = false } }) { Text("刷新 / 核验远端状态") }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onPlain) { Text("仅本次普通 PTY") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("返回") } },
    )
    killTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text("结束远端会话 ${target.name}？") },
            text = { Text("会话内的程序可能终止，其他连接此会话的客户端也会断开。此操作不是关闭本地标签。") },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                killTarget = null
                work { client.kill(target); refresh() }
            }) { Text("结束远端会话") } },
            dismissButton = { TextButton(onClick = { killTarget = null }) { Text("取消") } },
        )
    }
}
