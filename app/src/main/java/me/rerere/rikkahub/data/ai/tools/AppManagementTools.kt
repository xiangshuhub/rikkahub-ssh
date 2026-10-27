package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.preferences.ToolApprovalPreferences
import me.rerere.rikkahub.data.skills.SkillFrontmatterParser
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.rikkahub.data.skills.SkillMetadata
import java.io.File
import java.nio.file.Files
import kotlin.uuid.Uuid

/** Human-opted-in configuration tools. No permission, credential or current-chat mutation API. */
class AppManagementTools internal constructor(
    private val settingsStore: SettingsStore,
    private val skills: ManagementSkillAccess,
    private val removeMcpClient: suspend (McpServerConfig) -> Unit,
    private val permissionSnapshot: suspend () -> Set<String>,
    private val backups: File,
    private val workspaceSnapshot: suspend () -> List<ManagementWorkspace> = { emptyList() },
) {
    constructor(context: Context, settingsStore: SettingsStore, skills: SkillManager, mcp: McpManager,
        workspaces: me.rerere.rikkahub.data.repository.WorkspaceRepository) : this(
        settingsStore, skills.managementAccess(), mcp::removeClient,
        ToolApprovalPreferences(context)::managementPermissions,
        File(context.noBackupFilesDir, "app-management-backups"),
        { workspaces.managementWorkspaceSnapshot() },
    )
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }

    suspend fun tools(): List<Tool> {
        val allowed = permissionSnapshot()
        return listOf("assistants", "skills", "mcp").filter { it in allowed }.map { category ->
            Tool(
                name = "app_management_$category",
                description = when (category) {
                    "assistants" -> "Manage global assistant profiles. Actions: list, read, create, update, read_backup, list_workspaces, preview_workspace_binding. Workspace binding requires assistants AND workspace_bindings permissions. First preview_workspace_binding with id (existing assistant) or name (new assistant), workspace_id (UUID or none), scope=new_conversations. It returns workspace_confirmation and if_revision; copy them into create/update with the same workspace_id. This binds/switches/unbinds only, never deletes a workspace or changes an existing conversation. Binding does not verify connectivity. read_backup accepts backup_id from a previous edit and returns safe profile fields for recovery. Read returns revision; update requires if_revision. Writes require scope=new_conversations and human approval; never modify existing conversation snapshots. Optional skill_names/mcp_ids replace bindings and require the corresponding permission. Never put credentials in prompts."
                    "skills" -> "Manage Skills. Actions: list, read, save, delete, read_backup. read_backup accepts backup_id and returns previous SKILL.md content for recovery; attachments remain in the private backup. save accepts full SKILL.md content and if_revision=new for creation, or the latest revision for editing. Names use lowercase letters, digits and hyphens. Built-in Skills are read-only. Read before deletion; pass the exact delete_confirmation and revision returned. All writes require human approval."
                    else -> "Manage MCP configurations. Actions: list, create, delete. Create accepts name, url and transport (streamable_http or sse), always disabled with no credentials; the user must enable/login in settings. List returns revisions and delete_confirmation. Deletion requires both; removes configuration, stored credentials, connection and global assistant bindings. Never disclose or request tokens in tool arguments."
                },
                parameters = { schema(category) },
                needsApproval = ::managementNeedsApproval,
                execute = { args ->
                    withContext(Dispatchers.IO) {
                        mutex.withLock {
                            requirePermission(category)
                            val result = when (category) {
                                "assistants" -> assistantAction(args.jsonObject)
                                "skills" -> skillAction(args.jsonObject)
                                else -> mcpAction(args.jsonObject)
                            }
                            listOf(UIMessagePart.Text(result.toString()))
                        }
                    }
                },
            )
        }
    }

    private suspend fun requirePermission(category: String) {
        check(category in permissionSnapshot()) { "Management permission revoked or not enabled: $category" }
    }

    private fun schema(category: String): InputSchema.Obj {
        val fields = when (category) {
            "assistants" -> listOf("action", "id", "name", "system_prompt", "model_id", "scope", "if_revision", "backup_id", "workspace_id", "workspace_confirmation")
            "skills" -> listOf("action", "name", "content", "if_revision", "delete_confirmation", "backup_id")
            else -> listOf("action", "id", "name", "url", "transport", "if_revision", "delete_confirmation")
        }
        return InputSchema.Obj(properties = buildJsonObject {
            fields.forEach { key -> put(key, buildJsonObject {
                put("type", "string")
                when (key) {
                    "action" -> put("enum", JsonArray((when (category) {
                        "assistants" -> listOf("list", "read", "create", "update", "read_backup", "list_workspaces", "preview_workspace_binding")
                        "skills" -> listOf("list", "read", "save", "delete", "read_backup")
                        else -> listOf("list", "create", "delete")
                    }).map(::JsonPrimitive)))
                    "scope" -> put("enum", JsonArray(listOf(JsonPrimitive("new_conversations"))))
                    "transport" -> put("enum", JsonArray(listOf(JsonPrimitive("streamable_http"), JsonPrimitive("sse"))))
                    "if_revision" -> put("description", "Required for update/save/delete. Copy revision from read/list; use new only when creating a Skill.")
                    "delete_confirmation" -> put("description", "Required for deletion: copy exact human-readable impact statement from read/list. It is not an approval; the user must still approve the tool call.")
                    "workspace_id" -> put("description", "Workspace UUID from list_workspaces, or none to unbind. Omit to preserve current binding. Requires preview and workspace_confirmation.")
                    "workspace_confirmation" -> put("description", "Exact impact statement returned by preview_workspace_binding. User approval is still required.")
                    "model_id" -> put("description", "Model UUID from assistant list, or default to use the global model.")
                    "content" -> put("description", "Full SKILL.md with YAML name and description frontmatter. No script execution.")
                }
            }) }
            if (category == "assistants") {
                listOf("skill_names", "mcp_ids").forEach { key -> put(key, buildJsonObject {
                    put("type", "array"); put("items", buildJsonObject { put("type", "string") })
                }) }
            }
        }, required = listOf("action"))
    }

    private fun JsonObject.text(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: error("Missing $key")
    private fun JsonObject.optional(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.strings(key: String): Set<String>? = this[key]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet()
    private fun assistantRevision(assistant: Assistant) = managementRevision(json.encodeToString(assistant))
    private fun assistantSummary(assistant: Assistant, full: Boolean = false) = buildJsonObject {
        put("id", assistant.id.toString()); put("name", assistant.name)
        put("revision", assistantRevision(assistant))
        put("workspace_id", assistant.workspaceId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        if (full) {
            put("system_prompt", assistant.systemPrompt)
            put("model_id", assistant.chatModelId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
            put("skill_names", JsonArray(assistant.enabledSkills.sorted().map(::JsonPrimitive)))
            put("mcp_ids", JsonArray(assistant.mcpServers.map { JsonPrimitive(it.toString()) }))
        }
    }

    private suspend fun assistantAction(args: JsonObject): JsonElement {
        val action = args.text("action")
        val settings = settingsStore.awaitLoaded()
        if (action == "list_workspaces") {
            requirePermission("workspace_bindings")
            return JsonArray(workspaceSnapshot().map { it.publicFields() })
        }
        if (action == "preview_workspace_binding") {
            requirePermission("workspace_bindings")
            requireNewConversationScope(args.text("scope"))
            val old = args.optional("id")?.let { id -> settings.assistants.single { it.id.toString() == id } }
            val name = old?.name ?: args.text("name").also(::validateManagementName)
            val target = parseWorkspaceBinding(args.text("workspace_id"))
            val workspaces = workspaceSnapshot()
            return buildJsonObject {
                put("workspace_confirmation", workspaceBindingConfirmation(name, old?.id, old?.workspaceId, target, workspaces))
                put("workspace_id", target?.toString() ?: "none")
                old?.let { put("if_revision", assistantRevision(it)) }
                put("scope", "new_conversations")
            }
        }
        if (action == "read_backup") {
            val backup = validatedBackup(args.text("backup_id"), "assistant", ".json")
            return assistantSummary(json.decodeFromString<Assistant>(backup.readText()), true)
        }
        if (action == "list") return buildJsonObject {
            put("assistants", JsonArray(settings.assistants.map { assistantSummary(it) }))
            put("models", JsonArray(settings.providers.flatMap { it.models }.map { model -> buildJsonObject {
                put("id", model.id.toString()); put("name", model.displayName); put("model_id", model.modelId)
            } }))
        }
        if (action == "read") return assistantSummary(settings.assistants.single { it.id.toString() == args.text("id") }, true)
        require(action in setOf("create", "update")) { "Unsupported assistant action" }
        requireNewConversationScope(args.text("scope"))
        val changesWorkspace = "workspace_id" in args
        val workspaceId = if (changesWorkspace) {
            requirePermission("workspace_bindings")
            parseWorkspaceBinding(args.text("workspace_id"))
        } else null
        val names = args.strings("skill_names")
        val serverIds = args.strings("mcp_ids")?.map { Uuid.parse(it) }?.toSet()
        if (names != null) {
            requirePermission("skills")
            require(names.all { skills.findSkill(it) != null }) { "Unknown Skill" }
        }
        if (serverIds != null) requirePermission("mcp")
        args.optional("system_prompt")?.let { require(it.length <= 100_000) { "Prompt too large" } }
        val backupId = if (action == "update") {
            val old = settings.assistants.single { it.id.toString() == args.text("id") }
            requireManagementRevision(assistantRevision(old), args.text("if_revision"))
            backupText("assistant", json.encodeToString(old))
        } else null
        // Recheck permission and target metadata after backup and immediately before persistence.
        val workspaces = if (changesWorkspace) {
            requirePermission("workspace_bindings")
            workspaceSnapshot()
        } else emptyList()
        if (changesWorkspace) requirePermission("workspace_bindings")
        requirePermission("assistants")
        var saved: Assistant? = null
        settingsStore.update { latest ->
            val old = if (action == "create") Assistant(name = args.text("name"))
                else latest.assistants.single { it.id.toString() == args.text("id") }.also {
                    requireManagementRevision(assistantRevision(it), args.text("if_revision"))
                }
            if (changesWorkspace) {
                val expected = workspaceBindingConfirmation(old.name, old.id.takeIf { action != "create" },
                    old.workspaceId, workspaceId, workspaces)
                check(args.text("workspace_confirmation") == expected) {
                    "Workspace binding impact changed. Preview again and request a new confirmation."
                }
            }
            val name = args.optional("name") ?: old.name
            validateManagementName(name)
            require(latest.assistants.none { it.id != old.id && it.name == name }) { "Assistant name already exists" }
            val modelId = args.optional("model_id")?.let { if (it == "default") null else Uuid.parse(it) }
            if (modelId != null) require(latest.providers.any { provider -> provider.models.any { it.id == modelId } }) { "Unknown model ID" }
            if (serverIds != null) require(serverIds.all { id -> latest.mcpServers.any { it.id == id } }) { "Unknown MCP server" }
            val updated = old.copy(
                name = name,
                systemPrompt = args.optional("system_prompt") ?: old.systemPrompt,
                chatModelId = if ("model_id" in args) modelId else old.chatModelId,
                enabledSkills = names ?: old.enabledSkills,
                mcpServers = serverIds ?: old.mcpServers,
                workspaceId = if (changesWorkspace) workspaceId else old.workspaceId,
            )
            saved = updated
            latest.copy(assistants = if (action == "create") latest.assistants + updated else latest.assistants.map { if (it.id == old.id) updated else it })
        }
        return buildJsonObject {
            put("assistant", assistantSummary(checkNotNull(saved), true))
            put("scope", "new_conversations")
            backupId?.let { put("backup_id", it) }
            put("note", "Global profile saved. Existing conversation snapshots are unchanged; start a new conversation to use it.")
        }
    }

    private fun skillFiles(skill: SkillMetadata): List<File> {
        val root = skill.skillDir.canonicalFile
        require(!Files.isSymbolicLink(skill.skillDir.toPath())) { "Symlink Skills cannot be managed" }
        val files = skill.skillDir.walkTopDown().onEnter { dir ->
            require(!Files.isSymbolicLink(dir.toPath()) && (dir.canonicalFile == root || dir.canonicalPath.startsWith(root.path + File.separator)))
            true
        }.filter { file ->
            require(!Files.isSymbolicLink(file.toPath())) { "Symlink files cannot be managed" }
            file.isFile
        }.take(257).toList()
        require(files.size <= 256 && files.sumOf { it.length() } <= 10 * 1024 * 1024) { "Skill too large for safe management" }
        return files.sortedBy { it.relativeTo(skill.skillDir).path }
    }

    private fun skillRevision(skill: SkillMetadata): String = managementRevision(skillFiles(skill).joinToString("\n") {
        it.relativeTo(skill.skillDir).path + ":" + me.rerere.workspace.ssh.textRevision(it.readBytes())
    })

    private fun affected(settings: Settings, predicate: (Assistant) -> Boolean): String =
        settings.assistants.filter(predicate).sortedBy { it.id.toString() }.joinToString(", ") { "${it.name} (${it.id})" }.ifEmpty { "无" }

    private fun skillDeleteConfirmation(skill: SkillMetadata, settings: Settings): String =
        "删除 Skill「${skill.name}」及全部附属文件；移除助手绑定：${affected(settings) { skill.name in it.enabledSkills }}。现有对话不重写；删除的 Skill 将不可读取。"

    private suspend fun skillAction(args: JsonObject): JsonElement {
        val action = args.text("action")
        if (action == "read_backup") {
            val backup = validatedBackup(args.text("backup_id"), "skill", "")
            return buildJsonObject {
                put("content", File(backup, "SKILL.md").readText())
                put("note", "SKILL.md recovery only. Full attachment files are retained in the private backup. Saving this content still requires a new confirmation and current revision.")
            }
        }
        if (action == "list") return JsonArray(skills.listSkills().map { skill -> buildJsonObject {
            put("name", skill.name); put("description", skill.description); put("builtin", skill.builtin)
        } })
        val name = args.text("name")
        val skill = skills.findSkill(name)
        if (action == "read") {
            val found = skill ?: error("Skill not found")
            return buildJsonObject {
                put("name", name); put("builtin", found.builtin)
                put("revision", skillRevision(found)); put("content", found.skillFile.readText())
                put("files", JsonArray(skillFiles(found).map { JsonPrimitive(it.relativeTo(found.skillDir).path) }))
                if (!found.builtin) put("delete_confirmation", skillDeleteConfirmation(found, settingsStore.awaitLoaded()))
            }
        }
        require(action in setOf("save", "delete")) { "Unsupported Skill action" }
        check(skill?.builtin != true) { "Built-in Skills cannot be changed or deleted" }
        val expected = args.text("if_revision")
        if (skill == null) {
            require(action == "save" && expected == "new") { "Skill not found; creation requires if_revision=new" }
        } else requireManagementRevision(skillRevision(skill), expected)
        if (action == "delete") {
            val found = checkNotNull(skill)
            check(args.text("delete_confirmation") == skillDeleteConfirmation(found, settingsStore.awaitLoaded())) { "Deletion scope changed; read again for confirmation" }
            val backup = backupSkill(found)
            requirePermission("skills")
            requireManagementRevision(skillRevision(found), expected)
            check(args.text("delete_confirmation") == skillDeleteConfirmation(found, settingsStore.awaitLoaded())) { "Deletion scope changed during backup; read again" }
            check(skills.deleteSkill(found)) { "Skill deletion failed" }
            settingsStore.update { latest -> latest.copy(assistants = latest.assistants.map {
                it.copy(enabledSkills = it.enabledSkills - name)
            }) }
            return buildJsonObject { put("deleted", name); put("backup_id", backup) }
        }
        validateManagedSkillName(name)
        val content = args.text("content")
        require(content.length <= 100_000) { "Skill content too large" }
        val frontmatter = SkillFrontmatterParser.parse(content)
        require(frontmatter["name"] == name && !frontmatter["description"].isNullOrBlank()) { "SKILL.md needs matching name and nonempty description in YAML frontmatter" }
        if (skill != null) require(skill.skillDir.name == name) { "Skill directory/name mismatch; edit manually" }
        else require(!File(skills.getSkillsDir(), name).exists()) { "Skill directory already exists; no overwrite" }
        val backup = skill?.let(::backupSkill)
        if (skill != null) requireManagementRevision(skillRevision(skill), expected)
        requirePermission("skills")
        val saved = if (skill == null) skills.saveSkill(name, content) ?: error("Skill save failed") else {
            // saveSkill replaces the whole directory. Only replace SKILL.md on edit,
            // retaining scripts, references, assets and their file modes.
            check(skills.saveSkillFile(name, "SKILL.md", content)) { "Skill save failed" }
            skills.findSkill(name) ?: error("Saved Skill cannot be read")
        }
        return buildJsonObject {
            put("name", saved.name); put("revision", skillRevision(saved))
            backup?.let { put("backup_id", it) }
        }
    }

    private fun mcpRevision(config: McpServerConfig) = managementRevision(json.encodeToString(config))
    private fun mcpDeleteConfirmation(config: McpServerConfig, settings: Settings): String =
        "删除 MCP「${config.commonOptions.name}」(${config.id})，断开连接并删除本地凭据；移除助手绑定：${affected(settings) { config.id in it.mcpServers }}。现有对话不重写；此服务器将不可调用。"

    private suspend fun mcpAction(args: JsonObject): JsonElement {
        val settings = settingsStore.awaitLoaded()
        return when (args.text("action")) {
            "list" -> JsonArray(settings.mcpServers.map { config -> buildJsonObject {
                // Existing URLs/headers/OAuth may contain secrets. Expose only allow-listed fields.
                managedMcpPublicFields(config).forEach { (key, value) -> put(key, value) }
                put("revision", mcpRevision(config))
                put("delete_confirmation", mcpDeleteConfirmation(config, settings))
            } })
            "create" -> {
                val name = args.text("name")
                require(Regex("[A-Za-z0-9]{1,64}").matches(name)) { "MCP name must be 1–64 ASCII letters or digits (tool namespace requirement)" }
                val url = validateManagedMcpUrl(args.text("url"))
                val options = McpCommonOptions(name = name, enable = false)
                val config = when (args.text("transport")) {
                    "sse" -> McpServerConfig.SseTransportServer(commonOptions = options, url = url)
                    "streamable_http" -> McpServerConfig.StreamableHTTPServer(commonOptions = options, url = url)
                    else -> error("Unsupported MCP transport")
                }
                settingsStore.update { latest ->
                    require(latest.mcpServers.none { it.commonOptions.name == name }) { "MCP name already exists" }
                    latest.copy(mcpServers = latest.mcpServers + config)
                }
                buildJsonObject { put("id", config.id.toString()); put("name", name); put("enabled", false); put("note", "Configure credentials, authorize and enable manually in MCP settings.") }
            }
            "delete" -> {
                val id = Uuid.parse(args.text("id"))
                var removed: McpServerConfig? = null
                settingsStore.update { latest ->
                    val config = latest.mcpServers.single { it.id == id }
                    requireManagementRevision(mcpRevision(config), args.text("if_revision"))
                    check(args.text("delete_confirmation") == mcpDeleteConfirmation(config, latest)) { "Deletion scope changed; list again for confirmation" }
                    removed = config
                    removeManagedMcp(latest, id)
                }
                // removeClient cancels reconnects and forgets pending OAuth state. Do not call
                // clearAuthorization here: that method re-adds a client after clearing credentials.
                removeMcpClient(checkNotNull(removed))
                buildJsonObject { put("deleted_id", id.toString()); put("credentials_removed", true) }
            }
            else -> error("Unsupported MCP action")
        }
    }

    private fun validatedBackup(id: String, kind: String, suffix: String): File {
        require(id.startsWith("$kind-")) { "Wrong backup category" }
        Uuid.parse(id.removePrefix("$kind-"))
        val file = File(backups, id + suffix)
        require(file.exists() && file.canonicalFile.parentFile == backups.canonicalFile) { "Backup not found" }
        return file
    }

    private fun backupText(kind: String, text: String): String {
        val id = "$kind-${Uuid.random()}"
        val file = File(backups, "$id.json")
        check(backups.isDirectory || backups.mkdirs()) { "Cannot create backup directory" }
        file.writeText(text)
        return id
    }

    private fun backupSkill(skill: SkillMetadata): String {
        val files = skillFiles(skill)
        val id = "skill-${Uuid.random()}"
        val directory = File(backups, id)
        check(directory.mkdirs()) { "Cannot create Skill backup" }
        files.forEach { source ->
            val target = File(directory, source.relativeTo(skill.skillDir).path)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = false)
        }
        return id
    }
}
