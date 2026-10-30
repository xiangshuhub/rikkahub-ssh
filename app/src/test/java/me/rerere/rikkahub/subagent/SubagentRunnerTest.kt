package me.rerere.rikkahub.subagent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.*
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class SubagentRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val model = Model(modelId = "fake", tools = setOf(BuiltInTools.Search))
    private val setting = ProviderSetting.OpenAI(models = listOf(model))
    private val config = SubagentSettings(enabled = true)
    private val profile = SubagentProfile("worker", systemPrompt = "Only inspect the provided facts", readOnly = false)
    private fun record() = SubagentRun(owner = SubagentOwner(Uuid.random(), Uuid.random()), agent = "worker",
        task = "isolated task", modelId = model.id, modelName = model.modelId, background = false)
    private fun child(tools: List<Tool> = emptyList()) = PreparedChild(profile, "isolated task", model, setting, tools)
    private fun text(value: String) = listOf(StreamChunk.TextStart("t"), StreamChunk.TextDelta("t", value), StreamChunk.TextEnd("t"), StreamChunk.Finish("stop"))
    private fun call(name: String, id: String = "call", input: String = "{}") = listOf(StreamChunk.ToolCallStart(id, name), StreamChunk.ToolCallDelta(id, inputDelta = input), StreamChunk.ToolCallEnd(id), StreamChunk.Finish("tool_calls"))
    private class Fake(val script: (Int, List<UIMessage>, TextGenerationParams) -> List<StreamChunk>) : Provider<ProviderSetting> {
        var requests = 0
        override suspend fun listModels(providerSetting: ProviderSetting) = emptyList<Model>()
        override suspend fun generateText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): TextGenerationResult = error("stream only")
        override suspend fun streamText(providerSetting: ProviderSetting, messages: List<UIMessage>, params: TextGenerationParams): Flow<StreamChunk> = flow {
            script(++requests, messages, params).forEach { emit(it) }
        }
    }

    @Test fun `fresh context own session and disabled native tools`() = runTest {
        val record = record()
        val fake = Fake { _, messages, params ->
            assertEquals(2, messages.size)
            assertEquals("isolated task", messages.last().toText())
            assertTrue(messages.first().toText().contains("isolated child agent"))
            assertTrue(messages.first().toText().contains(profile.systemPrompt))
            assertEquals(record.id.toString(), params.sessionId)
            assertTrue(params.model.tools.isEmpty())
            assertTrue(params.customBody.isEmpty())
            assertEquals(2048, params.maxTokens)
            text("final answer")
        }
        val result = runSubagent(record, child(), config, fake, {}, {})
        assertEquals(SubagentStatus.COMPLETED, result.status)
        assertEquals("final answer", result.output)
        assertEquals(1, fake.requests)
    }

    @Test fun `approval-needed child tool never executes and reports blocked`() = runTest {
        var executed = false
        val tool = Tool("workspace_write_file", "write", needsApproval = { true }, execute = { executed = true; listOf(UIMessagePart.Text("wrote")) })
        val fake = Fake { _, _, _ -> call(tool.name) }
        val result = runSubagent(record(), child(listOf(tool)), config.copy(trustedTools = false), fake, {}, {})
        assertEquals(SubagentStatus.BLOCKED, result.status)
        assertTrue(result.error!!.contains(tool.name))
        assertFalse(executed)
        assertEquals(1, fake.requests)
    }

    @Test fun `authorized tool loop returns final text not intermediate narration`() = runTest {
        var executed = 0
        val tool = Tool("workspace_read_file", "read", execute = { executed++; listOf(UIMessagePart.Text("evidence")) })
        var latest: SubagentProgress? = null
        val fake = Fake { step, _, _ ->
            (if (step == 1) text("checking...").dropLast(1) + call(tool.name) else text("verified result")) + StreamChunk.Usage(TokenUsage(10, 5))
        }
        val result = runSubagent(record(), child(listOf(tool)), config, fake, {}, { latest = it })
        assertEquals(SubagentStatus.COMPLETED, result.status)
        assertEquals("verified result", result.output)
        assertEquals(1, executed)
        assertEquals(2, latest!!.steps)
        assertEquals(20, latest!!.inputTokens)
        assertEquals(10, latest!!.outputTokens)
    }

    @Test fun `step budget stops infinite tool loop`() = runTest {
        var executions = 0
        val tool = Tool("workspace_read_file", "read", execute = { executions++; listOf(UIMessagePart.Text("evidence")) })
        val fake = Fake { step, _, _ -> call(tool.name, step.toString()) }
        val result = runSubagent(record(), child(listOf(tool)), config.copy(maxSteps = 2), fake, {}, {})
        assertEquals(SubagentStatus.STEP_LIMIT, result.status)
        assertEquals(2, fake.requests)
        assertEquals(2, executions)
    }

    @Test fun `runner independently removes delegation tools`() = runTest {
        val delegation = Tool("subagent_dispatch", "spawn", execute = { error("Must never execute") })
        val fake = Fake { _, _, params ->
            assertTrue(params.tools.none { it.name.startsWith("subagent") })
            text("no delegation")
        }
        assertEquals(SubagentStatus.COMPLETED, runSubagent(record(), child(listOf(delegation)), config, fake, {}, {}).status)
    }

    @Test fun `trusted mode executes authorized tools without approval prompts`() = runTest {
        var writes = 0
        val tool = Tool("workspace_write_file", "write", needsApproval = { true }, execute = { writes++; listOf(UIMessagePart.Text("done")) })
        val fake = Fake { step, _, _ -> if (step == 1) call(tool.name) else text("completed") }
        assertEquals(SubagentStatus.COMPLETED, runSubagent(record(), child(listOf(tool)), config, fake, {}, {}).status)
        assertEquals(1, writes)
    }

    @Test fun `supervisor answer is delivered as a correlated tool result`() = runTest {
        val fake = Fake { step, messages, _ ->
            if (step == 1) call("contact_supervisor", "q123", """{"question":"target?"}""") else {
                assertEquals("target A", (messages.last().getTools().single().output.single() as UIMessagePart.Text).text)
                text("used target A")
            }
        }
        val result = runSubagent(record(), child(), config, fake, {}, {}, ask = { key, question ->
            assertEquals("q123", key); assertEquals("target?", question); "target A"
        })
        assertEquals("used target A", result.output)
    }

    @Test fun `tool intent is durable before execution and resume does not repeat completed writes`() = runTest {
        val journal = SubagentJournal(temporary.newFolder())
        val record = record()
        var writes = 0
        val tool = Tool("workspace_write_file", "write", execute = {
            assertEquals(CheckpointPhase.TOOL_IN_FLIGHT, journal.load(record.id)!!.phase)
            writes++
            listOf(UIMessagePart.Text("written once"))
        })
        val interrupted = Fake { step, _, _ -> if (step == 1) call(tool.name) else error("network interrupted") }
        assertTrue(runCatching { runSubagent(record, child(listOf(tool)), config, interrupted, {}, {}, journal = journal) }.isFailure)
        val checkpoint = journal.load(record.id)!!
        assertTrue(checkpoint.safeToResume)
        assertTrue(checkpoint.messages.last().getTools().single().isExecuted)
        val resumed = Fake { _, messages, _ ->
            assertTrue(messages.last().getTools().single().isExecuted)
            text("finished without rewriting")
        }
        val result = runSubagent(record, child(listOf(tool)), config, resumed, {}, {}, journal = journal, checkpoint = checkpoint)
        assertEquals(SubagentStatus.COMPLETED, result.status)
        assertEquals(1, writes)
        assertEquals(CheckpointPhase.FINISHED, journal.load(record.id)!!.phase)
        assertTrue(journal.readTrace(record.id).contains("tool_end"))
    }

    @Test fun `checkpoint failure after a write preserves the uncertainty fence`() = runTest {
        val journal = SubagentJournal(temporary.newFolder())
        val record = record()
        var writes = 0
        val tool = Tool("workspace_write_file", "write", execute = {
            writes++
            listOf(UIMessagePart.Text("x".repeat(4 * 1024 * 1024 + 1)))
        })
        val fake = Fake { _, _, _ -> call(tool.name) }
        assertTrue(runCatching { runSubagent(record, child(listOf(tool)), config, fake, {}, {}, journal = journal) }.isFailure)
        assertEquals(1, writes)
        val checkpoint = journal.load(record.id)!!
        assertEquals(CheckpointPhase.TOOL_IN_FLIGHT, checkpoint.phase)
        assertFalse(checkpoint.safeToResume)
    }

    @Test fun `unsafe checkpoints never invoke either model or tool`() = runTest {
        val checkpoint = SubagentCheckpoint(record().id, profile, config, model.id, setOf("workspace_write_file"), null, null,
            listOf(UIMessage.user("task")), CheckpointPhase.TOOL_IN_FLIGHT, "workspace_write_file")
        val fake = Fake { _, _, _ -> error("must not call model") }
        assertTrue(runCatching { runSubagent(record(), child(), config, fake, {}, {}, checkpoint = checkpoint) }.isFailure)
        assertEquals(0, fake.requests)
    }

    @Test fun `only explicitly delegated coordinators receive child orchestration tools`() = runTest {
        val dispatch = Tool("subagent_dispatch", "delegate", execute = { emptyList() })
        val coordinator = child().copy(profile = profile.copy(canDelegate = true), coordinationTools = listOf(dispatch))
        val fake = Fake { _, _, params ->
            assertTrue(params.tools.any { it.name == "subagent_dispatch" })
            text("coordinated")
        }
        assertEquals(SubagentStatus.COMPLETED, runSubagent(record(), coordinator, config, fake, {}, {}).status)
        val leaf = Fake { _, _, params ->
            assertTrue(params.tools.none { it.name == "subagent_dispatch" }); text("leaf")
        }
        runSubagent(record().copy(depth = config.maxDepth), coordinator, config, leaf, {}, {})
        assertEquals(1, leaf.requests)
    }

    @Test fun `forwarded dependency reader is limited to explicitly delegated report ids`() = runTest {
        val dependency = Uuid.random()
        var reads = 0
        val fake = Fake { step, _, _ ->
            if (step == 1) call("read_dependency", "dep", """{"id":"$dependency","offset":0}""") else text("reviewed full report")
        }
        val result = runSubagent(record(), child().copy(dependencies = setOf(dependency)), config, fake, {}, {},
            readDependency = { id, offset -> assertEquals(dependency, id); assertEquals(0, offset); reads++; "full evidence" })
        assertEquals(SubagentStatus.COMPLETED, result.status)
        assertEquals(1, reads)
        val unauthorized = Fake { step, _, _ ->
            if (step == 1) call("read_dependency", "dep", """{"id":"${Uuid.random()}"}""") else text("request denied")
        }
        runSubagent(record(), child().copy(dependencies = setOf(dependency)), config, unauthorized, {}, {},
            readDependency = { _, _ -> error("Unauthorized report must never be read") })
        assertEquals(2, unauthorized.requests)
    }

    @Test fun `empty model response is not reported as a successful handoff`() = runTest {
        val fake = Fake { _, _, _ -> listOf(StreamChunk.Finish("stop")) }
        assertEquals(SubagentStatus.FAILED, runSubagent(record(), child(), config, fake, {}, {}).status)
    }
}
