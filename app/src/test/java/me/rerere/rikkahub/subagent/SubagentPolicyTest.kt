package me.rerere.rikkahub.subagent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.APP_MANAGEMENT_ASSISTANT_ID
import me.rerere.rikkahub.data.model.managementOnly
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.uuid.Uuid

class SubagentPolicyTest {
    @get:Rule val directory = TemporaryFolder()
    private fun tool(name: String, approval: Boolean = false) = Tool(name, name,
        needsApproval = { approval }, execute = { listOf(UIMessagePart.Text("ok")) })

    @Test fun `read-only is a runtime allowlist and excludes shell and opaque MCP`() {
        val parent = listOf("workspace_read_file", "workspace_write_file", "workspace_shell", "javascript", "mcp__server__write", "search_web", "subagent_dispatch").map { tool(it) }
        val selected = childTools(parent, SubagentProfile("reviewer"), null)
        assertEquals(listOf("workspace_read_file", "search_web"), selected.map { it.name })
        assertTrue(runCatching { childTools(parent, SubagentProfile("reviewer"), setOf("workspace_shell")) }.isFailure)
    }

    @Test fun `worker narrows parent authority but never expands it or removes approval`() {
        val write = tool("workspace_write_file", approval = true)
        val parent = listOf(write, tool("workspace_read_file"), tool("subagent_dispatch"))
        val profile = SubagentProfile("worker", readOnly = false)
        val selected = childTools(parent, profile, setOf(write.name))
        assertEquals(listOf(write.name), selected.map { it.name })
        assertTrue(selected.single().needsApproval(JsonObject(emptyMap())))
        assertTrue(childTools(parent, profile, emptySet()).isEmpty())
        assertFalse(childTools(parent, profile, null).any { it.name == "subagent_dispatch" })
        assertTrue(runCatching { childTools(parent, profile, setOf("not_owned")) }.isFailure)
        assertEquals(listOf("workspace_read_file"), childTools(parent, profile.copy(allowedTools = setOf("workspace_read_file")), null).map { it.name })
    }

    @Test fun `ordinary YOLO switch cannot silently authorize delegation`() {
        assertTrue(SubagentSettings().autoApproveDispatch)
        assertTrue(SubagentSettings().trustedTools)
        assertFalse(me.rerere.rikkahub.data.preferences.shouldAutoApproveTool("subagent_dispatch", true))
        assertTrue(me.rerere.rikkahub.data.preferences.shouldAutoApproveTool("workspace_read_file", true))
    }

    @Test fun `tool fanout has a separate execution budget shared by all child tools`() = runBlocking {
        var executed = 0
        val tools = boundedChildTools(listOf("read", "write").map { name ->
            Tool(name, name, execute = { executed++; listOf(UIMessagePart.Text("ok")) })
        }, limit = 2)
        tools[0].execute(JsonObject(emptyMap()))
        tools[1].execute(JsonObject(emptyMap()))
        assertTrue(runCatching { tools[0].execute(JsonObject(emptyMap())) }.isFailure)
        assertEquals(2, executed)
    }

    @Test fun `configuration rejects invalid bounds and ambiguous names`() {
        SubagentSettings().validate()
        for (invalid in listOf(SubagentSettings(maxConcurrent = 5), SubagentSettings(timeoutSeconds = 0), SubagentSettings(maxSteps = 25),
            SubagentSettings(maxOutputTokens = 100_000), SubagentSettings(profiles = listOf(SubagentProfile("a"), SubagentProfile("A"))),
            SubagentSettings(profiles = listOf(SubagentProfile("bad name"))))) {
            assertTrue(runCatching { invalid.validate() }.isFailure)
        }
    }

    @Test fun `model override is strict and profile selection does not silently fall back`() {
        val parent = Model(modelId = "parent", displayName = "Shared")
        val other = Model(modelId = "other", displayName = "Shared")
        val settings = Settings(providers = listOf(ProviderSetting.OpenAI(models = listOf(parent, other))))
        val profile = SubagentProfile("worker", modelId = other.id)
        assertEquals(other, resolveSubagentModel(settings, parent, profile, null))
        assertEquals(parent, resolveSubagentModel(settings, parent, profile, parent.id.toString()))
        assertEquals(other, resolveSubagentModel(settings, parent, profile, "other"))
        assertTrue(runCatching { resolveSubagentModel(settings, parent, profile, "Shared") }.isFailure)
        assertTrue(runCatching { resolveSubagentModel(settings, parent, profile.copy(modelId = Uuid.random()), null) }.isFailure)
        assertTrue(runCatching { resolveSubagentModel(settings.copy(providers = settings.providers.map { it.copyProvider(enabled = false) }), parent, profile, null) }.isFailure)
    }

    @Test fun `legacy assistant defaults off and manager cannot acquire delegation`() {
        val assistant = Json.decodeFromString<Assistant>("{}")
        assertFalse(assistant.subagents.enabled)
        val manager = assistant.copy(id = APP_MANAGEMENT_ASSISTANT_ID, subagents = assistant.subagents.copy(enabled = true))
        assertFalse(manager.managementOnly().subagents.enabled)
    }

    @Test fun `atomic ledger round trips without credentials or partial files`() = runBlocking {
        val file = File(directory.root, "private/runs.json")
        val store = FileSubagentStore(file)
        assertTrue(store.load().isEmpty())
        val record = SubagentRun(owner = SubagentOwner(Uuid.random(), Uuid.random()), agent = "reviewer", task = "task",
            modelId = Uuid.random(), modelName = "fake", background = true, status = SubagentStatus.COMPLETED, output = "result")
        store.save(listOf(record))
        assertEquals(listOf(record), store.load())
        assertFalse(File(file.parentFile, "runs.json.tmp").exists())
        assertFalse(file.readText().contains("apiKey"))
        store.save(emptyList())
        assertTrue(store.load().isEmpty())
    }

    @Test fun `malformed ledger is not silently discarded`() = runBlocking {
        val file = directory.newFile("runs.json")
        file.writeText("corrupt record")
        val store = FileSubagentStore(file)
        assertTrue(runCatching { store.load() }.isFailure)
        assertEquals("corrupt record", file.readText())
    }
}
