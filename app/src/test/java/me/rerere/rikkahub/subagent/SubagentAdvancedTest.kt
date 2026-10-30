package me.rerere.rikkahub.subagent

import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.uuid.Uuid

class SubagentAdvancedTest {
    @get:Rule val folder = TemporaryFolder()
    private val owner = SubagentOwner(Uuid.random(), Uuid.random())
    private val config = SubagentSettings(enabled = true)
    private fun run(parent: SubagentRun? = null) = SubagentRun(owner = owner, agent = "worker", task = "task", modelId = Uuid.random(), modelName = "fake",
        background = true, parentRunId = parent?.id, depth = (parent?.depth ?: 0) + 1, budgetGroupId = parent?.budgetGroupId ?: parent?.id)
    private class Store(var records: List<SubagentRun> = emptyList()) : SubagentStore {
        override suspend fun load() = records
        override suspend fun save(runs: List<SubagentRun>) { records = runs }
    }

    @Test fun `supervisor wait returns a question and replies are correlated exactly once`() = runTest {
        val service = SubagentCoordinator(backgroundScope, Store(), StandardTestDispatcher(testScheduler))
        val record = run()
        service.launch(record, config) {
            val answer = service.ask(record.id, "q1", "Which target?")
            SubagentOutcome(SubagentStatus.COMPLETED, answer)
        }
        val waiting = service.wait(owner, record.id)
        assertEquals(SubagentStatus.WAITING_INPUT, waiting.status)
        assertEquals("q1", waiting.questionKey)
        assertTrue(runCatching { service.reply(owner.copy(conversationId = Uuid.random()), record.id, "q1", "bad") }.isFailure)
        assertTrue(runCatching { service.reply(owner, record.id, "old-question", "bad") }.isFailure)
        service.reply(owner, record.id, "q1", "target A")
        assertTrue(runCatching { service.reply(owner, record.id, "q1", "duplicate") }.isFailure)
        assertEquals("target A", service.wait(owner, record.id).output)
    }

    @Test fun `nested visibility is confined to the controllers descendants`() {
        val root = run()
        val child = run(root)
        val grandchild = run(child)
        val sibling = run(root)
        val records = listOf(root, child, grandchild, sibling)
        assertTrue(isVisibleSubagent(grandchild, records, child.id))
        assertFalse(isVisibleSubagent(root, records, child.id))
        assertFalse(isVisibleSubagent(sibling, records, child.id))
        assertFalse(isVisibleSubagent(child, records, child.id))
    }

    @Test fun `revoking an ancestor role revokes descendant execution authority`() {
        val parent = run().copy(agent = "coordinator")
        val child = run(parent)
        val assistant = Assistant(id = owner.assistantId)
        val settings = Settings(assistants = listOf(assistant), subagents = config)
        assertTrue(isSubagentRoleChainEnabled(child, listOf(parent, child), settings))
        val revoked = settings.copy(subagents = config.copy(profiles = config.profiles.map {
            if (it.name == "coordinator") it.copy(canDelegate = false) else it
        }))
        assertFalse(isSubagentRoleChainEnabled(child, listOf(parent, child), revoked))
        assertFalse(isSubagentRoleChainEnabled(child, listOf(child), settings))
    }

    @Test fun `old cancellation cannot restart implicitly but explicit later resume is a new bounded authorization`() {
        val parent = run().copy(parentSuppressed = true, cancelEpoch = 10)
        val child = run(parent).copy(startEpoch = 9)
        assertTrue(isSuppressedSubagent(child, listOf(parent, child)))
        val resumed = child.copy(startEpoch = 11, parentSuppressed = false)
        assertFalse(isSuppressedSubagent(resumed, listOf(parent, resumed)))
        assertTrue(isSuppressedSubagent(resumed, listOf(parent.copy(cancelEpoch = 12), resumed)))
    }

    @Test fun `cancellation intent survives restart rather than becoming automatically resumable`() = runTest {
        val cancelled = run().copy(status = SubagentStatus.CANCELLING, parentSuppressed = true, cancelEpoch = 10)
        val service = SubagentCoordinator(backgroundScope, Store(listOf(cancelled)), StandardTestDispatcher(testScheduler))
        assertEquals(SubagentStatus.CANCELLED, service.get(owner, cancelled.id).status)
    }

    @Test fun `parent cancellation cancels descendants and depth cannot be forged`() = runTest {
        val service = SubagentCoordinator(backgroundScope, Store(), StandardTestDispatcher(testScheduler))
        val parent = service.launch(run(), config) { awaitCancellation() }
        val child = service.launch(run(parent), config) { awaitCancellation() }
        assertTrue(runCatching { service.launch(run(child), config) { awaitCancellation() } }.isFailure)
        service.cancel(owner, parent.id)
        assertEquals(SubagentStatus.CANCELLED, service.get(owner, child.id).status)
    }

    @Test fun `safe resume reuses identity and preserves usage instead of restarting accounting`() = runTest {
        val old = run().copy(status = SubagentStatus.PROCESS_LOST, inputTokens = 123)
        val service = SubagentCoordinator(backgroundScope, Store(listOf(old)), StandardTestDispatcher(testScheduler))
        service.launch(old, config, resume = true) { SubagentOutcome(SubagentStatus.COMPLETED, "resumed") }
        val finished = service.wait(owner, old.id)
        assertEquals(2, finished.attempts)
        assertEquals(123, finished.inputTokens)
        assertEquals(1, service.list(owner).size)
        assertTrue(runCatching { service.launch(old, config) { error("duplicate") } }.isFailure)
    }

    @Test fun `usage reservations across siblings are atomic and rejected reservations do not burn budget`() = runTest {
        val service = SubagentCoordinator(backgroundScope, Store(), StandardTestDispatcher(testScheduler))
        val group = Uuid.random()
        val first = service.launch(run().copy(budgetGroupId = group), config) { awaitCancellation() }
        val second = service.launch(run().copy(budgetGroupId = group), config) { awaitCancellation() }
        val limit = config.copy(maxTotalTokens = 2000)
        service.account(first.id, limit, SubagentProgress("", 1, 1000, 500), reservation = true)
        assertTrue(runCatching { service.account(second.id, limit, SubagentProgress("", 1, 1000, 500), reservation = true) }.exceptionOrNull() is SubagentBudgetExceeded)
        assertEquals(0, service.get(owner, second.id).inputTokens)
        service.account(first.id, limit, SubagentProgress("", 1, 100, 50))
        service.account(second.id, limit, SubagentProgress("", 1, 1000, 500), reservation = true)
        assertEquals(1000, service.get(owner, second.id).inputTokens)
        assertTrue(runCatching { service.account(second.id, limit, SubagentProgress("", 1, 2500, 500)) }.isFailure)
        assertEquals(2500, service.get(owner, second.id).inputTokens) // Actual overrun is retained, not hidden.
    }

    @Test fun `automatic delivery is deduplicated and repeated questions share a wake budget`() = runTest {
        val saved = run().copy(status = SubagentStatus.COMPLETED, eventRevision = 1)
        val service = SubagentCoordinator(backgroundScope, Store(listOf(saved)), StandardTestDispatcher(testScheduler))
        assertTrue(service.observed(owner, saved.id))
        assertFalse(service.observed(owner, saved.id))
        repeat(4) { assertTrue(service.claimParentWake(owner, setOf(saved.id))) }
        assertFalse(service.claimParentWake(owner, setOf(saved.id)))
        assertEquals(4, service.get(owner, saved.id).parentWakeHistory.size)
    }

    @Test fun `fork removes orchestration and system instructions and is bounded`() {
        val messages = listOf(UIMessage.system("parent-only secret system"), UIMessage.user("facts"), UIMessage.assistant("review this"))
        val fork = forkSubagentContext(messages)
        assertEquals(2, fork.size)
        assertFalse(fork.any { it.toText().contains("secret system") })
        assertTrue(forkSubagentContext(messages, maxChars = 1).isEmpty())
        assertEquals(3, messages.size)
    }

    @Test fun `unknown tool side effects cannot be replayed but read-only checkpoints can`() {
        val checkpoint = SubagentCheckpoint(Uuid.random(), SubagentProfile("worker"), config, Uuid.random(), emptySet(), null, null,
            listOf(UIMessage.user("task")), CheckpointPhase.TOOL_IN_FLIGHT, "workspace_write_file")
        assertFalse(checkpoint.safeToResume)
        assertFalse(checkpoint.copy(inFlightTool = "workspace_shell").safeToResume)
        assertTrue(checkpoint.copy(inFlightTool = "workspace_read_file").safeToResume)
        assertTrue(checkpoint.copy(inFlightTool = "contact_supervisor").safeToResume)
    }

    @Test fun `checkpoint and trace survive a new journal instance`() = runBlocking {
        val directory = folder.newFolder()
        val journal = SubagentJournal(directory)
        val checkpoint = SubagentCheckpoint(Uuid.random(), SubagentProfile("worker"), config, Uuid.random(), setOf("read"), null, null,
            listOf(UIMessage.user("task")), CheckpointPhase.READY, inputTokens = 42)
        journal.save(checkpoint)
        journal.trace(checkpoint.runId, "tool_start", "read args")
        val fresh = SubagentJournal(directory)
        assertEquals(checkpoint, fresh.load(checkpoint.runId))
        assertTrue(fresh.readTrace(checkpoint.runId).contains("tool_start"))
        fresh.delete(listOf(checkpoint.runId))
        assertNull(fresh.load(checkpoint.runId))
    }

    @Test fun `token and cost budgets reserve before requests and reconcile reported usage`() {
        val profile = SubagentProfile("worker", inputUsdPerMillion = 10.0, outputUsdPerMillion = 20.0)
        val budget = SubagentBudget(config.copy(maxCostUsd = 1.0), profile)
        val max = budget.reserve(listOf(UIMessage.user("task")))
        assertEquals(config.maxOutputTokens, max)
        assertTrue(budget.inputTokens > 0)
        budget.settle(TokenUsage(100, 50))
        assertEquals(0.002, budget.cost!!, 0.0000001)
        assertTrue(runCatching { SubagentBudget(config.copy(maxCostUsd = 1.0), SubagentProfile("unpriced")) }.isFailure)
        val exhausted = SubagentBudget(config.copy(maxTotalTokens = 1024), profile)
        assertTrue(runCatching { exhausted.reserve(listOf(UIMessage.user("task"))) }.exceptionOrNull() is SubagentBudgetExceeded)
    }

    @Test fun `workflow chains reports and pauses without replaying completed stages`() = runTest {
        val stages = Json.parseToJsonElement("""[{"tasks":[{"agent":"scout","task":"inspect"}]},{"tasks":[{"agent":"reviewer","task":"review"}]},{"tasks":[{"agent":"worker","task":"fix"}]}]""").jsonArray
        var calls = 0
        val result = executeSubagentWorkflow(stages) { input ->
            calls++
            assertEquals(false, input.jsonObject["background"]!!.jsonPrimitive.boolean)
            if (calls == 2) assertTrue(input.toString().contains("first evidence"))
            listOf(UIMessagePart.Text(if (calls == 1) """[{"id":"one","status":"COMPLETED","output":"first evidence"}]""" else """[{"id":"two","status":"WAITING_INPUT","question":"target?"}]"""))
        }
        val output = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
        assertEquals(2, calls)
        assertEquals("PAUSED_OR_PARTIAL", output["status"]!!.jsonPrimitive.content)
        assertEquals(1, output["remaining_stages"]!!.jsonArray.size)
    }

    @Test fun `invalid workflows cannot execute even their first stage`() = runTest {
        var calls = 0
        val invalid = Json.parseToJsonElement("""[{"tasks":[{"agent":"worker","task":"valid"}]},{"tasks":[]}]""").jsonArray
        assertTrue(runCatching { executeSubagentWorkflow(invalid) { calls++; emptyList() } }.isFailure)
        assertEquals(0, calls)
    }
}
