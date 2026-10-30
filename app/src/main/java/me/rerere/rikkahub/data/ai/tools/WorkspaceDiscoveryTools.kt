package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceStorageArea

/** Native read-only discovery: no model-supplied shell, regex engine, or executable command. */
internal fun createWorkspaceDiscoveryTools(
    workspaceId: String, layout: WorkspaceLayout, repository: WorkspaceRepository,
    needsApproval: (String) -> Boolean, cwd: String?,
): List<Tool> {
    val defaultPath = if (cwd?.startsWith('/') == true) cwd else layout.absolutePath(WorkspaceStorageArea.FILES, cwd.orEmpty())
    fun path(args: JsonObject): String = discoveryPath(args["path"]?.jsonPrimitive?.contentOrNull ?: defaultPath)
    suspend fun list(path: String): List<WorkspaceFileEntry> {
        val (area, relative) = discoveryLocation(layout, path)
        return repository.listFiles(workspaceId, area, relative).map { entry ->
            entry.copy(path = discoveryPath(layout.absolutePath(area, entry.path)))
        }
    }
    return listOf(
        Tool("workspace_list_files", "List a directory without running a shell. Default: $defaultPath. Absolute paths; offset/limit paginate the backend's bounded listing. backend_limit_reached means more entries may exist.",
            parameters = { InputSchema.Obj(buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("offset", buildJsonObject { put("type", "integer"); put("minimum", 0) })
                put("limit", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 100) })
            }) }, needsApproval = { needsApproval("workspace_list_files") }, execute = { value ->
                val args = value.jsonObject
                val root = path(args)
                val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
                val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 80
                require(offset >= 0 && limit in 1..100)
                val entries = list(root)
                val page = mutableListOf<JsonElement>()
                var bytes = 0
                for (entry in entries.drop(offset).take(limit)) {
                    val item = buildJsonObject { put("path", "/${entry.path.trimStart('/')}"); put("directory", entry.isDirectory); put("bytes", entry.sizeBytes) }
                    if (bytes + item.toString().length > 24_000) break
                    page += item; bytes += item.toString().length
                }
                val next = offset + page.size
                listOf(UIMessagePart.Text(buildJsonObject {
                    put("path", root); put("entries", JsonArray(page)); put("backend_limit_reached", entries.size >= 500)
                    if (next < entries.size) put("next_offset", next)
                }.toString()))
            }),
        Tool("workspace_search", "Bounded literal text search without a shell. Default path: $defaultPath. Scans at most 200 files / 100 directories / depth 6 / 10 MiB / 20 seconds; skips common build directories and files over 256 KiB. Check partial before treating absence as evidence.",
            parameters = { InputSchema.Obj(buildJsonObject {
                put("path", buildJsonObject { put("type", "string") })
                put("query", buildJsonObject { put("type", "string"); put("maxLength", 256) })
                put("case_sensitive", buildJsonObject { put("type", "boolean") })
            }, listOf("query")) }, needsApproval = { needsApproval("workspace_search") }, execute = { value ->
                val args = value.jsonObject
                val result = searchWorkspaceText(path(args), args["query"]?.jsonPrimitive?.content ?: error("query required"),
                    args["case_sensitive"]?.jsonPrimitive?.booleanOrNull ?: false,
                    list = ::list, read = { repository.readToolDocument(workspaceId, it).text })
                listOf(UIMessagePart.Text(result.toString()))
            }),
    )
}

internal fun discoveryPath(path: String): String {
    require(path.startsWith('/') && '\u0000' !in path && path.split('/').none { it == ".." }) { "Use an absolute path without traversal" }
    return "/" + path.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
}

internal fun discoveryLocation(layout: WorkspaceLayout, path: String): Pair<WorkspaceStorageArea, String> {
    val absolute = discoveryPath(path)
    val root = discoveryPath(layout.workspaceDir)
    return if (root == "/" || absolute == root || absolute.startsWith("$root/")) {
        WorkspaceStorageArea.FILES to absolute.removePrefix(root).trimStart('/')
    } else WorkspaceStorageArea.LINUX to absolute.trimStart('/')
}

internal suspend fun searchWorkspaceText(
    path: String, query: String, caseSensitive: Boolean,
    list: suspend (String) -> List<WorkspaceFileEntry>, read: suspend (String) -> String,
): JsonObject {
    val root = discoveryPath(path)
    require(query.isNotBlank() && query.length <= 256)
    val queue = ArrayDeque<Pair<String, Int>>().apply { add(root to 0) }
    val seen = mutableSetOf<String>()
    val matches = mutableListOf<JsonElement>()
    var files = 0; var directories = 0; var bytes = 0L; var outputChars = 0; var skipped = 0; var partial = false
    val excluded = setOf(".git", "node_modules", ".gradle", "build", "dist", ".venv", "__pycache__")
    val completed = withTimeoutOrNull(20_000) {
        while (queue.isNotEmpty() && files < 200 && directories < 100 && bytes < 10 * 1024 * 1024 && matches.size < 50) {
            val (directory, depth) = queue.removeFirst()
            if (!seen.add(directory)) continue
            directories++
            val entries = try { list(directory) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { partial = true; skipped++; continue }
            if (entries.size >= 500) partial = true
            for (entry in entries) {
                val absolute = try { discoveryPath("/${entry.path.trimStart('/')}") } catch (_: IllegalArgumentException) { partial = true; skipped++; continue }
                if (root != "/" && absolute != root && !absolute.startsWith("$root/")) { partial = true; continue }
                if (entry.name in excluded) { skipped++; continue }
                if (entry.isDirectory) {
                    if (depth < 6 && queue.size < 1000) queue.add(absolute to depth + 1) else { skipped++; partial = true }
                    continue
                }
                if (files >= 200 || bytes >= 10 * 1024 * 1024 || matches.size >= 50) { partial = true; break }
                if (entry.sizeBytes > 256 * 1024 || entry.sizeBytes < 0) { skipped++; continue }
                files++
                val text = try { read(absolute) } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { partial = true; skipped++; continue }
                val size = text.toByteArray().size
                bytes += size
                if (size > 256 * 1024) { skipped++; partial = true; continue }
                if ('\u0000' in text) { skipped++; continue }
                for ((index, line) in text.lineSequence().withIndex()) {
                    if (!line.contains(query, ignoreCase = !caseSensitive)) continue
                    val item = buildJsonObject { put("path", absolute); put("line", index + 1); put("text", line.take(300)); put("line_truncated", line.length > 300) }
                    if (outputChars + item.toString().length > 24_000 || matches.size >= 50) { partial = true; break }
                    matches += item; outputChars += item.toString().length
                }
                if (outputChars > 23_000) { partial = true; queue.clear(); break }
            }
        }
        true
    } == true
    return buildJsonObject {
        put("matches", JsonArray(matches)); put("files_scanned", files); put("directories_scanned", directories); put("skipped", skipped)
        put("partial", partial || !completed || queue.isNotEmpty() || skipped > 0 || files >= 200 || directories >= 100 || matches.size >= 50)
        put("timed_out", !completed)
    }
}
