package me.rerere.rikkahub.subagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.uuid.Uuid

@Serializable
internal enum class CheckpointPhase { READY, TOOL_READY, TOOL_IN_FLIGHT, FINISHED }

@Serializable
internal data class SubagentCheckpoint(
    val runId: Uuid,
    val profile: SubagentProfile,
    val config: SubagentSettings,
    val modelId: Uuid,
    val allowedTools: Set<String>,
    val workspaceId: Uuid?,
    val workspaceCwd: String?,
    val messages: List<UIMessage>,
    val phase: CheckpointPhase,
    val inFlightTool: String? = null,
    val toolCalls: Int = 0,
    val steps: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val finishedOutput: String? = null,
    val workspaceIdentity: String? = null,
    val dependencies: Set<Uuid> = emptySet(),
) {
    val safeToResume: Boolean get() = phase != CheckpointPhase.TOOL_IN_FLIGHT || inFlightTool in setOf(
        "contact_supervisor", "workspace_read_file", "workspace_list_files", "workspace_search", "get_time_info", "conversation_read", "conversation_search", "recent_chats", "use_skill",
        "subagent_status", "subagent_wait", "subagent_trace", "read_dependency",
    )
}

@Serializable
internal data class SubagentTrace(val time: Long, val kind: String, val text: String)

/** Each run owns its checkpoint/log; paths are UUID-derived, never supplied by a model. */
internal class SubagentJournal(private val directory: File) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    suspend fun save(checkpoint: SubagentCheckpoint) = withContext(Dispatchers.IO) { lock.withLock {
        directory.mkdirs()
        val bytes = json.encodeToString(checkpoint).toByteArray()
        if (bytes.size > 4 * 1024 * 1024) throw SubagentInfrastructureException("子任务检查点超过 4 MiB；请缩小工具读取范围或分拆任务")
        if ((directory.listFiles()?.sumOf { it.length() } ?: 0L) + bytes.size > 256L * 1024 * 1024) {
            throw SubagentInfrastructureException("子代理日志存储已达到 256 MiB，请清理已结束任务记录")
        }
        val target = File(directory, "${checkpoint.runId}.json")
        val temp = File(directory, "${checkpoint.runId}.tmp")
        temp.outputStream().use { it.write(bytes); it.fd.sync() }
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    } }
    suspend fun load(id: Uuid): SubagentCheckpoint? = withContext(Dispatchers.IO) { lock.withLock {
        val file = File(directory, "$id.json")
        if (!file.exists()) return@withLock null
        check(file.length() <= 4 * 1024 * 1024) { "Child checkpoint is too large" }
        json.decodeFromString<SubagentCheckpoint>(file.readText()).also { require(it.runId == id) }
    } }
    suspend fun trace(id: Uuid, kind: String, text: String) = withContext(Dispatchers.IO) { lock.withLock {
        directory.mkdirs()
        val file = File(directory, "$id.jsonl")
        // Stop instead of silently dropping oldest evidence. The UI exposes truncation explicitly.
        if (file.length() >= 8 * 1024 * 1024 || (directory.listFiles()?.sumOf { it.length() } ?: 0L) >= 128L * 1024 * 1024) {
            File(directory, "$id.truncated").writeText("budget")
            return@withLock
        }
        val bounded = if (text.length > 262_144) text.take(262_100) + "\n[trace entry truncated]" else text
        val entry = (json.encodeToString(SubagentTrace(System.currentTimeMillis(), kind, bounded)) + "\n").toByteArray()
        if (file.length() + entry.size > 8L * 1024 * 1024 || (directory.listFiles()?.sumOf { it.length() } ?: 0L) + entry.size > 128L * 1024 * 1024) {
            File(directory, "$id.truncated").writeText("budget")
            return@withLock
        }
        file.appendBytes(entry)
    } }
    suspend fun readTrace(id: Uuid, offset: Int = 0): String = withContext(Dispatchers.IO) { lock.withLock {
        require(offset in 0..(9 * 1024 * 1024)) { "Invalid trace offset" }
        val file = File(directory, "$id.jsonl")
        if (!file.exists()) return@withLock if (File(directory, "$id.truncated").exists()) "日志因存储预算限制未记录" else "暂无日志"
        java.io.RandomAccessFile(file, "r").use { stream ->
            stream.seek(offset.toLong())
            val bytes = ByteArray(65536)
            val count = stream.read(bytes).coerceAtLeast(0)
            String(bytes, 0, count, Charsets.UTF_8) + "\n[next_offset=${offset + count}; total_bytes=${file.length()}; log_cap=8MiB; trace_truncated=${File(directory, "$id.truncated").exists()}]"
        }
    } }
    suspend fun pruneOrphans(retained: Set<Uuid>) = withContext(Dispatchers.IO) { lock.withLock {
        val cutoff = System.currentTimeMillis() - 3_600_000
        directory.listFiles()?.forEach { file ->
            val id = runCatching { Uuid.parse(file.nameWithoutExtension) }.getOrNull()
            if (id != null && id !in retained && file.extension in setOf("json", "jsonl", "tmp", "truncated") && file.lastModified() < cutoff) file.delete()
        }
    } }
    suspend fun delete(ids: Collection<Uuid>) = withContext(Dispatchers.IO) { lock.withLock {
        ids.forEach { id -> listOf("json", "jsonl", "tmp", "truncated").forEach { extension -> File(directory, "$id.$extension").delete() } }
    } }
}

internal fun forkSubagentContext(messages: List<UIMessage>, maxChars: Int = 64_000): List<UIMessage> {
    var size = 0
    return messages.asReversed().mapNotNull { message ->
        if (message.role == MessageRole.SYSTEM) return@mapNotNull null
        val parts = message.parts.filter { part -> when (part) {
            is UIMessagePart.Tool -> part.isExecuted && !part.toolName.startsWith("subagent") && part.toolName != "contact_supervisor"
            is UIMessagePart.Text, is UIMessagePart.Image -> true
            else -> false
        } }
        if (parts.isEmpty()) return@mapNotNull null
        val copy = message.copy(parts = parts, usage = null, lastUsage = null, stepCount = null)
        val length = Json.encodeToString(copy).length
        if (size + length > maxChars) return@mapNotNull null
        size += length
        copy
    }.asReversed()
}
