package me.rerere.rikkahub.subagent

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

private const val WORKTREE_GIT = "git -c core.hooksPath=/dev/null -c core.fsmonitor=false"

/** Managed detached worktrees, not an OS sandbox. No force removal, reset, stash or auto-commit. */
internal class SubagentWorktrees {
    private val lock = Mutex()
    suspend fun prepare(run: SubagentRun, shell: Tool, record: suspend (SubagentWorktree) -> Unit): SubagentWorktree = lock.withLock {
        require(run.parentRunId == null) { "Nested isolated worktrees are not supported; delegate isolated writers from the root" }
        val info = command(shell, "$WORKTREE_GIT rev-parse --show-toplevel && $WORKTREE_GIT rev-parse --absolute-git-dir && $WORKTREE_GIT rev-parse HEAD").trim().lines()
        require(info.size == 3 && info[2].matches(Regex("[a-f0-9]{40,64}"))) { "Invalid Git repository metadata" }
        val root = info[0]; val git = info[1]
        require(root.startsWith('/') && git.startsWith('/'))
        val status = command(shell, "$WORKTREE_GIT -C ${quote(root)} status --porcelain --untracked-files=all")
        if (status.isNotBlank()) throw SubagentInfrastructureException("工作树隔离要求父仓库干净；不会自动 stash、提交或丢弃已有修改")
        val registered = command(shell, "$WORKTREE_GIT -C ${quote(root)} worktree list --porcelain")
        val managed = registered.lineSequence().count { line ->
            line.startsWith("worktree ") && line.substringBeforeLast('/').endsWith("/rikkahub-subagents") &&
                runCatching { kotlin.uuid.Uuid.parse(line.substringAfterLast('/')) }.isSuccess
        }
        if (managed >= 8) throw SubagentInfrastructureException("该仓库已保留 8 个托管工作树；请核验补丁后手动清理，不会自动删除修改")
        val path = "$git/rikkahub-subagents/${run.id}"
        val tree = SubagentWorktree(root, path, info[2])
        record(tree) // Journal allocation intent before mutating Git metadata.
        command(shell, "test ! -e ${quote(path)} && $WORKTREE_GIT -C ${quote(root)} worktree add --detach ${quote(path)} ${quote(info[2])}")
        tree
    }

    suspend fun capture(tree: SubagentWorktree, shell: Tool): SubagentWorktree = lock.withLock {
        val patch = "${tree.path}.patch"
        // Staging is confined to the owned child worktree and includes previously untracked files.
        val hash = command(shell, "$WORKTREE_GIT -C ${quote(tree.path)} add -A && $WORKTREE_GIT -C ${quote(tree.path)} diff --cached --binary ${quote(tree.baseCommit)} > ${quote(patch)} && $WORKTREE_GIT -C ${quote(tree.repository)} hash-object ${quote(patch)}").trim()
        require(hash.matches(Regex("[a-f0-9]{40,64}"))) { "Patch capture failed" }
        tree.copy(patch = patch, patchHash = hash)
    }

    suspend fun apply(tree: SubagentWorktree, shell: Tool): String = lock.withLock {
        val patch = tree.patch ?: error("No captured patch")
        val hash = tree.patchHash ?: error("No patch integrity marker")
        command(shell, "test \"\$($WORKTREE_GIT -C ${quote(tree.repository)} rev-parse HEAD)\" = ${quote(tree.baseCommit)} && " +
            "test \"\$($WORKTREE_GIT -C ${quote(tree.repository)} hash-object ${quote(patch)})\" = ${quote(hash)} && " +
            "$WORKTREE_GIT -C ${quote(tree.repository)} apply --check ${quote(patch)} && $WORKTREE_GIT -C ${quote(tree.repository)} apply ${quote(patch)}")
        "Patch applied without committing. Worktree and patch retained at ${tree.path}"
    }

    fun route(tools: List<Tool>, tree: SubagentWorktree): List<Tool> = tools.map { tool ->
        when (tool.name) {
            "workspace_shell" -> tool.copy(description = tool.description + " This child runs in isolated worktree ${tree.path}.", execute = { value ->
                val args = value.jsonObject
                val cwd = args["cwd"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val path = if (cwd.isBlank()) tree.path else worktreePath(tree, cwd)
                val original = args["command"]?.jsonPrimitive?.content ?: error("command required")
                tool.execute(JsonObject((args - "cwd") + ("command" to JsonPrimitive("cd ${quote(path)} && {\n$original\n}"))))
            })
            "workspace_list_files", "workspace_search" -> tool.copy(
                description = tool.description + " The child's default directory is ${tree.path}.",
                execute = { value ->
                    val args = value.jsonObject
                    val path = args["path"]?.jsonPrimitive?.contentOrNull ?: tree.path
                    tool.execute(JsonObject(args + ("path" to JsonPrimitive(worktreePath(tree, path)))))
                })
            "workspace_read_file", "workspace_write_file", "workspace_edit_file" -> tool.copy(
                description = tool.description + " Use paths under ${tree.path}; parent repository paths are mapped into this worktree.",
                execute = { value ->
                    val args = value.jsonObject
                    val path = args["path"]?.jsonPrimitive?.content ?: error("path required")
                    tool.execute(JsonObject(args + ("path" to JsonPrimitive(worktreePath(tree, path)))))
                })
            else -> tool
        }
    }

    private suspend fun command(shell: Tool, command: String): String {
        val result = shell.execute(buildJsonObject { put("command", command); put("timeout", 60) })
            .filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        val body = Json.parseToJsonElement(result).jsonObject
        if (body["exitCode"]?.jsonPrimitive?.intOrNull != 0 || body["timedOut"]?.jsonPrimitive?.booleanOrNull == true || body["truncated"]?.jsonPrimitive?.booleanOrNull == true) {
            throw SubagentInfrastructureException("Git 操作失败；未执行强制清理或回滚，请用工作区工具检查连接、权限、冲突及补丁")
        }
        return body["stdout"]?.jsonPrimitive?.content.orEmpty()
    }
}

internal fun worktreePath(tree: SubagentWorktree, path: String): String {
    require('\u0000' !in path && '\n' !in path && path.split('/').none { it == ".." }) { "Invalid worktree path" }
    return when {
        path == tree.path || path.startsWith("${tree.path}/") -> path
        path == tree.repository -> tree.path
        path.startsWith("${tree.repository}/") -> tree.path + path.removePrefix(tree.repository)
        !path.startsWith('/') -> "${tree.path}/$path"
        else -> error("File path is outside the isolated worktree")
    }
}

internal fun quote(value: String): String {
    require('\u0000' !in value && '\n' !in value && '\r' !in value)
    return "'" + value.replace("'", "'\\''") + "'"
}
