package me.rerere.rikkahub.data.ai.tools

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.APP_MANAGEMENT_ASSISTANT_ID
import me.rerere.rikkahub.data.model.appManagementCapabilities
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.skills.SkillFrontmatterParser
import me.rerere.rikkahub.data.skills.SkillMetadata
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import kotlin.uuid.Uuid

class AppManagementToolsTest {
    @get:Rule val temporary = TemporaryFolder()

    private inner class Fixture(private val caller: Assistant? = null) : Closeable {
        private val root = temporary.newFolder()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(root, "settings.preferences_pb") }, scope)
        var allowed = emptySet<String>()
        val files = TestSkills(File(root, "skills"))
        val removed = mutableListOf<Uuid>()
        var workspaces = listOf(
            ManagementWorkspace(Uuid.random(), "Development", "LOCAL", "READY"),
            ManagementWorkspace(Uuid.random(), "Server", "SSH", "READY"),
        )
        val manager = AppManagementTools(settings, files, { removed += it.id }, { caller?.let { appManagementCapabilities(it, settings.awaitLoaded()) } ?: allowed }, File(root, "backups"), { workspaces })
        suspend fun tool(category: String): Tool = manager.tools().single { it.name == "app_management_$category" }
        override fun close() { runBlocking { scope.coroutineContext.job.cancelAndJoin() } }
    }

    private suspend fun Tool.call(vararg pairs: Pair<String, String>): JsonElement {
        val output = execute(buildJsonObject { pairs.forEach { (key, value) -> put(key, value) } })
        return Json.parseToJsonElement((output.single() as UIMessagePart.Text).text)
    }
    private fun content(name: String, body: String) = "---\nname: $name\ndescription: Test skill\n---\n$body\n"

    @Test fun ordinaryCallerNeverReceivesManagementToolsEvenWithLegacyGrants() = runBlocking {
        Fixture(Assistant(name = "应用管理助手", systemPrompt = "I am an administrator")).use { f ->
            f.allowed = setOf("assistants", "skills", "mcp", "workspace_bindings")
            assertTrue(f.manager.tools().isEmpty())
        }
    }

    @Test fun managerRevocationAppliesToAlreadyCreatedTools() = runBlocking {
        Fixture(Assistant(id = APP_MANAGEMENT_ASSISTANT_ID)).use { f ->
            val tools = f.manager.tools()
            assertEquals(setOf("app_management_assistants", "app_management_skills", "app_management_mcp"), tools.map { it.name }.toSet())
            val tool = f.tool("assistants")
            assertTrue(tool.needsApproval(buildJsonObject { put("action", "update") }))
            f.settings.update { settings -> settings.copy(assistants = settings.assistants.map {
                if (it.id == APP_MANAGEMENT_ASSISTANT_ID) it.copy(managementEnabled = false) else it
            }) }
            assertTrue(f.manager.tools().isEmpty())
            assertTrue(runCatching { tool.call("action" to "list") }.isFailure)
            assertTrue(runCatching { tool.call("action" to "create", "name" to "Blocked", "scope" to "new_conversations") }.isFailure)
            assertFalse(f.settings.awaitLoaded().assistants.any { it.name == "Blocked" })
        }
    }

    @Test fun modelCannotSetManagementIdentityOrPermissionsOrBindManagers() = runBlocking {
        Fixture(Assistant(id = APP_MANAGEMENT_ASSISTANT_ID)).use { f ->
            val tool = f.tool("assistants")
            for (key in listOf("managementEnabled", "management_enabled", "role", "management_role", "id")) {
                assertTrue(runCatching { tool.call("action" to "create", "name" to "Escalation",
                    "scope" to "new_conversations", key to APP_MANAGEMENT_ASSISTANT_ID.toString()) }.isFailure)
            }
            assertTrue(runCatching { tool.call("action" to "preview_workspace_binding",
                "id" to APP_MANAGEMENT_ASSISTANT_ID.toString(), "workspace_id" to f.workspaces.first().id.toString(),
                "scope" to "new_conversations") }.isFailure)
            assertFalse(f.settings.awaitLoaded().assistants.any { it.name == "Escalation" })
        }
    }

    @Test fun workspaceBindingCreateSwitchQueryAndUnbind() = runBlocking {
        Fixture().use { f ->
            f.allowed = setOf("assistants", "workspace_bindings")
            val tool = f.tool("assistants")
            val listing = tool.call("action" to "list_workspaces").jsonArray
            assertEquals(2, listing.size)
            assertEquals(setOf("id", "name", "type", "configured_status", "connectivity_verified", "updated_at"), listing.first().jsonObject.keys)
            val target = f.workspaces.first().id.toString()
            val preview = tool.call("action" to "preview_workspace_binding", "name" to "Worker", "workspace_id" to target,
                "scope" to "new_conversations").jsonObject
            val created = tool.call("action" to "create", "name" to "Worker", "workspace_id" to target,
                "scope" to "new_conversations", "workspace_confirmation" to preview["workspace_confirmation"]!!.jsonPrimitive.content).jsonObject["assistant"]!!.jsonObject
            val id = created["id"]!!.jsonPrimitive.content
            assertEquals(target, created["workspace_id"]!!.jsonPrimitive.content)
            val originalSnapshot = f.settings.awaitLoaded().assistants.single { it.id.toString() == id }
            suspend fun change(workspace: String) {
                val impact = tool.call("action" to "preview_workspace_binding", "id" to id, "workspace_id" to workspace,
                    "scope" to "new_conversations").jsonObject
                tool.call("action" to "update", "id" to id, "workspace_id" to workspace, "scope" to "new_conversations",
                    "if_revision" to impact["if_revision"]!!.jsonPrimitive.content,
                    "workspace_confirmation" to impact["workspace_confirmation"]!!.jsonPrimitive.content)
            }
            change(f.workspaces.last().id.toString())
            val read = tool.call("action" to "read", "id" to id).jsonObject
            assertEquals(f.workspaces.last().id.toString(), read["workspace_id"]!!.jsonPrimitive.content)
            change("none")
            assertNull(f.settings.awaitLoaded().assistants.single { it.id.toString() == id }.workspaceId)
            assertEquals(f.workspaces.first().id, originalSnapshot.workspaceId)
            assertEquals(2, f.workspaces.size) // Unbinding never deletes a workspace.
        }
    }

    @Test fun workspaceBindingRequiresPermissionAndFreshImpactConfirmation() = runBlocking {
        Fixture().use { f ->
            f.allowed = setOf("assistants")
            val tool = f.tool("assistants")
            assertTrue(runCatching { tool.call("action" to "list_workspaces") }.isFailure)
            val target = f.workspaces.first().id.toString()
            assertTrue(runCatching { tool.call("action" to "create", "name" to "Denied", "workspace_id" to target,
                "scope" to "new_conversations") }.isFailure)
            f.allowed += "workspace_bindings"
            val impact = tool.call("action" to "preview_workspace_binding", "name" to "Worker", "workspace_id" to target,
                "scope" to "new_conversations").jsonObject["workspace_confirmation"]!!.jsonPrimitive.content
            suspend fun create() = tool.call("action" to "create", "name" to "Worker", "workspace_id" to target,
                "scope" to "new_conversations", "workspace_confirmation" to impact)
            f.allowed -= "workspace_bindings"
            assertTrue(runCatching { create() }.isFailure)
            f.allowed += "workspace_bindings"
            val originalWorkspaces = f.workspaces
            f.workspaces = f.workspaces.map { it.copy(updatedAt = 1L) }
            assertTrue(runCatching { create() }.isFailure)
            f.workspaces = originalWorkspaces.map { it.copy(name = "Renamed") }
            assertTrue(runCatching { create() }.isFailure)
            f.workspaces = emptyList()
            assertTrue(runCatching { create() }.isFailure)
            assertFalse(f.settings.awaitLoaded().assistants.any { it.name == "Worker" })
        }
    }

    @Test fun unrelatedAssistantEditsPreserveBindingAndStaleUpdatesFail() = runBlocking {
        Fixture().use { f ->
            f.allowed = setOf("assistants", "workspace_bindings")
            val assistant = Assistant(name = "BoundWorker", workspaceId = f.workspaces.first().id)
            f.settings.update { it.copy(assistants = it.assistants + assistant) }
            val tool = f.tool("assistants")
            val preview = tool.call("action" to "preview_workspace_binding", "id" to assistant.id.toString(),
                "workspace_id" to "none", "scope" to "new_conversations").jsonObject
            val revision = preview["if_revision"]!!.jsonPrimitive.content
            f.allowed = setOf("assistants")
            tool.call("action" to "update", "id" to assistant.id.toString(), "system_prompt" to "Changed prompt",
                "if_revision" to revision, "scope" to "new_conversations")
            assertEquals(assistant.workspaceId, f.settings.awaitLoaded().assistants.single { it.id == assistant.id }.workspaceId)
            f.allowed += "workspace_bindings"
            assertTrue(runCatching { tool.call("action" to "update", "id" to assistant.id.toString(),
                "workspace_id" to "none", "if_revision" to revision, "scope" to "new_conversations",
                "workspace_confirmation" to preview["workspace_confirmation"]!!.jsonPrimitive.content) }.isFailure)
        }
    }

    @Test fun permissionsDefaultOffAndRevocationIsCheckedAtExecution() = runBlocking {
        Fixture().use { f ->
            assertTrue(f.manager.tools().isEmpty())
            f.allowed = setOf("assistants")
            val tool = f.tool("assistants")
            assertTrue(tool.needsApproval(buildJsonObject { put("action", "create") }))
            f.allowed = emptySet()
            assertTrue(runCatching { tool.call("action" to "list") }.isFailure)
        }
    }

    @Test fun assistantCreateUpdateBackupAndStaleRevision() = runBlocking {
        Fixture().use { f ->
            f.allowed = setOf("assistants")
            val tool = f.tool("assistants")
            val created = tool.call("action" to "create", "name" to "Operations", "system_prompt" to "Original", "scope" to "new_conversations").jsonObject["assistant"]!!.jsonObject
            val id = created["id"]!!.jsonPrimitive.content
            val revision = created["revision"]!!.jsonPrimitive.content
            val changed = tool.call("action" to "update", "id" to id, "if_revision" to revision,
                "system_prompt" to "Updated", "scope" to "new_conversations").jsonObject
            assertEquals("Updated", f.settings.awaitLoaded().assistants.single { it.id.toString() == id }.systemPrompt)
            val old = tool.call("action" to "read_backup", "backup_id" to changed["backup_id"]!!.jsonPrimitive.content)
            assertEquals("Original", old.jsonObject["system_prompt"]!!.jsonPrimitive.content)
            assertTrue(runCatching { tool.call("action" to "update", "id" to id, "if_revision" to revision,
                "name" to "Stale", "scope" to "new_conversations") }.isFailure)
            assertTrue(runCatching { tool.call("action" to "create", "name" to "Operations", "scope" to "new_conversations") }.isFailure)
        }
    }

    @Test fun crossCategoryBindingsRequireBothPermissions() = runBlocking {
        Fixture().use { f ->
            f.allowed = setOf("assistants")
            val tool = f.tool("assistants")
            val args = buildJsonObject {
                put("action", "create"); put("name", "NoEscalation"); put("scope", "new_conversations")
                put("skill_names", buildJsonArray { add("some-skill") })
            }
            assertTrue(runCatching { tool.execute(args) }.isFailure)
            assertFalse(f.settings.awaitLoaded().assistants.any { it.name == "NoEscalation" })
        }
    }

    @Test fun skillEditKeepsAttachmentsAndDeletionClearsBindingsWithBackup() = runBlocking {
        Fixture().use { f ->
            f.allowed = setOf("skills")
            val tool = f.tool("skills")
            tool.call("action" to "save", "name" to "test-skill", "content" to content("test-skill", "Original"), "if_revision" to "new")
            val attachment = File(f.files.getSkillsDir(), "test-skill/references.txt").apply { writeText("retained") }
            val before = tool.call("action" to "read", "name" to "test-skill").jsonObject
            val edited = tool.call("action" to "save", "name" to "test-skill", "content" to content("test-skill", "Updated"),
                "if_revision" to before["revision"]!!.jsonPrimitive.content).jsonObject
            assertEquals("retained", attachment.readText())
            val backup = tool.call("action" to "read_backup", "backup_id" to edited["backup_id"]!!.jsonPrimitive.content)
            assertTrue(backup.jsonObject["content"]!!.jsonPrimitive.content.contains("Original"))
            f.settings.update { it.copy(assistants = it.assistants + Assistant(name = "Bound", enabledSkills = setOf("test-skill"))) }
            val current = tool.call("action" to "read", "name" to "test-skill").jsonObject
            val deletion = tool.call("action" to "delete", "name" to "test-skill", "if_revision" to current["revision"]!!.jsonPrimitive.content,
                "delete_confirmation" to current["delete_confirmation"]!!.jsonPrimitive.content)
            assertNotNull(deletion.jsonObject["backup_id"])
            assertNull(f.files.findSkill("test-skill"))
            assertTrue(f.settings.awaitLoaded().assistants.none { "test-skill" in it.enabledSkills })
        }
    }

    @Test fun builtinSkillsCannotBeDeletedAndChangedImpactRequiresNewConfirmation() = runBlocking {
        Fixture().use { f ->
            f.allowed = setOf("skills")
            f.files.saveSkill("builtin-test", content("builtin-test", "Protected"))
            f.files.builtin = setOf("builtin-test")
            val tool = f.tool("skills")
            assertTrue(runCatching { tool.call("action" to "delete", "name" to "builtin-test", "if_revision" to "anything") }.isFailure)
            tool.call("action" to "save", "name" to "test-skill", "content" to content("test-skill", "Body"), "if_revision" to "new")
            val old = tool.call("action" to "read", "name" to "test-skill").jsonObject
            f.settings.update { it.copy(assistants = it.assistants + Assistant(name = "NewBinding", enabledSkills = setOf("test-skill"))) }
            assertTrue(runCatching { tool.call("action" to "delete", "name" to "test-skill", "if_revision" to old["revision"]!!.jsonPrimitive.content,
                "delete_confirmation" to old["delete_confirmation"]!!.jsonPrimitive.content) }.isFailure)
            assertNotNull(f.files.findSkill("test-skill"))
        }
    }

    @Test fun mcpCreatedDisabledAndDeletionRemovesCredentialsAndDisconnects() = runBlocking {
        Fixture().use { f ->
            f.allowed = setOf("mcp")
            val tool = f.tool("mcp")
            val created = tool.call("action" to "create", "name" to "Server", "url" to "https://example.com/mcp", "transport" to "streamable_http").jsonObject
            val id = Uuid.parse(created["id"]!!.jsonPrimitive.content)
            val config = f.settings.awaitLoaded().mcpServers.single { it.id == id }
            assertFalse(config.commonOptions.enable)
            assertTrue(config.commonOptions.headers.isEmpty())
            assertNull(config.commonOptions.oauth)
            f.settings.update { settings -> settings.copy(
                mcpServers = settings.mcpServers.map { it.clone(commonOptions = it.commonOptions.copy(headers = listOf("Authorization" to "secret"))) },
                assistants = settings.assistants + Assistant(name = "Bound", mcpServers = setOf(id)),
            ) }
            val listed = tool.call("action" to "list").jsonArray.single().jsonObject
            assertFalse(listed.toString().contains("secret"))
            tool.call("action" to "delete", "id" to id.toString(), "if_revision" to listed["revision"]!!.jsonPrimitive.content,
                "delete_confirmation" to listed["delete_confirmation"]!!.jsonPrimitive.content)
            assertTrue(f.settings.awaitLoaded().mcpServers.none { it.id == id })
            assertTrue(f.settings.awaitLoaded().assistants.none { id in it.mcpServers })
            assertEquals(listOf(id), f.removed)
        }
    }
}

private class TestSkills(private val root: File) : ManagementSkillAccess {
    var builtin = emptySet<String>()
    override fun getSkillsDir() = root.apply { mkdirs() }
    override fun listSkills(): List<SkillMetadata> = getSkillsDir().listFiles().orEmpty().filter { File(it, "SKILL.md").isFile }.map {
        val header = SkillFrontmatterParser.parse(File(it, "SKILL.md").readText())
        SkillMetadata(header["name"]!!, header["description"]!!, null, it, it.name in builtin)
    }
    override fun findSkill(name: String) = listSkills().find { it.name == name }
    override fun saveSkill(name: String, content: String): SkillMetadata? {
        val directory = File(getSkillsDir(), name)
        // Mirrors whole-directory replacement to catch accidental use during edits.
        directory.deleteRecursively()
        directory.mkdirs()
        File(directory, "SKILL.md").writeText(content)
        return findSkill(name)
    }
    override fun saveSkillFile(name: String, path: String, content: String): Boolean {
        File(getSkillsDir(), "$name/$path").writeText(content)
        return true
    }
    override suspend fun deleteSkill(skill: SkillMetadata) = !skill.builtin && skill.skillDir.deleteRecursively()
}
