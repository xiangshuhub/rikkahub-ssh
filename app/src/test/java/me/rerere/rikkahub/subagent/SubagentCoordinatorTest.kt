package me.rerere.rikkahub.subagent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SubagentCoordinatorTest {
    private val owner = SubagentOwner(Uuid.random(), Uuid.random())
    private val config = SubagentSettings(enabled = true)
    private fun run(background: Boolean = true, scope: SubagentOwner = owner) = SubagentRun(
        owner = scope, agent = "scout", task = "Inspect only this task", modelId = Uuid.random(), modelName = "test", background = background)
    private class Store(var records: List<SubagentRun> = emptyList()) : SubagentStore {
        var failSave = false
        override suspend fun load() = records
        override suspend fun save(runs: List<SubagentRun>) {
            check(!failSave) { "disk unavailable" }
            records = runs.toList()
        }
    }

    @Test fun `background receipt is not completion and result can be collected`() = runTest {
        val store = Store()
        val service = SubagentCoordinator(backgroundScope, store, StandardTestDispatcher(testScheduler))
        val finish = CompletableDeferred<Unit>()
        val record = service.launch(run(), config) { finish.await(); SubagentOutcome(SubagentStatus.COMPLETED, "verified result") }
        assertTrue(record.status.active)
        assertTrue(store.records.any { it.id == record.id })
        finish.complete(Unit)
        val done = service.wait(owner, record.id)
        assertEquals(SubagentStatus.COMPLETED, done.status)
        assertEquals("verified result", done.output)
        assertEquals(done, store.records.single())
    }

    @Test fun `foreground waits and preserves bounded output and counters`() = runTest {
        val service = SubagentCoordinator(backgroundScope, Store(), StandardTestDispatcher(testScheduler))
        val result = service.launch(run(false), config) { progress ->
            progress(SubagentProgress("working", 2, 120, 30))
            SubagentOutcome(SubagentStatus.COMPLETED, "x".repeat(50_000))
        }
        assertEquals(SubagentStatus.COMPLETED, result.status)
        assertEquals(2, result.steps)
        assertEquals(120, result.inputTokens)
        assertTrue(result.output.length <= SUBAGENT_OUTPUT_LIMIT)
        assertTrue(result.output.endsWith("[Subagent output truncated]"))
    }

    @Test fun `cancelling the foreground caller cancels its child`() = runTest {
        val service = SubagentCoordinator(backgroundScope, Store(), StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        var stopped = false
        val record = run(false)
        val parent = launch { service.launch(record, config) {
            try { entered.complete(Unit); awaitCancellation() } finally { stopped = true }
        } }
        entered.await()
        parent.cancelAndJoin()
        assertTrue(stopped)
        assertEquals(SubagentStatus.CANCELLED, service.get(owner, record.id).status)
    }

    @Test fun `cancellation is active until child cleanup actually finishes`() = runTest {
        val service = SubagentCoordinator(backgroundScope, Store(), StandardTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val record = service.launch(run(), config) {
            try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { cleanup.await() } }
        }
        started.await()
        val cancelling = launch { service.cancel(owner, record.id) }
        runCurrent()
        assertEquals(SubagentStatus.CANCELLING, service.get(owner, record.id).status)
        service.clearFinished(owner.assistantId)
        assertEquals(1, service.list(owner).size)
        cleanup.complete(Unit)
        cancelling.join()
        assertEquals(SubagentStatus.CANCELLED, service.get(owner, record.id).status)
    }

    @Test fun `deadline terminates child and records timeout rather than success`() = runTest {
        val service = SubagentCoordinator(backgroundScope, Store(), StandardTestDispatcher(testScheduler))
        var stopped = false
        val result = service.launch(run(false), config.copy(timeoutSeconds = 30)) {
            try { delay(31_000); SubagentOutcome(SubagentStatus.COMPLETED, "too late") } finally { stopped = true }
        }
        assertTrue(stopped)
        assertEquals(SubagentStatus.TIMED_OUT, result.status)
    }

    @Test fun `task failure never persists provider exception secrets`() = runTest {
        val service = SubagentCoordinator(backgroundScope, Store(), StandardTestDispatcher(testScheduler))
        val result = service.launch(run(false), config) { error("Bearer secret-provider-token") }
        assertEquals(SubagentStatus.FAILED, result.status)
        assertFalse(result.error!!.contains("secret-provider-token"))
    }

    @Test fun `parent scope protects both observation and cancellation`() = runTest {
        val service = SubagentCoordinator(backgroundScope, Store(), StandardTestDispatcher(testScheduler))
        val record = service.launch(run(), config) { awaitCancellation() }
        val otherConversation = owner.copy(conversationId = Uuid.random())
        val otherAssistant = owner.copy(assistantId = Uuid.random())
        for (other in listOf(otherConversation, otherAssistant)) {
            assertTrue(service.list(other).isEmpty())
            assertTrue(runCatching { service.get(other, record.id) }.isFailure)
            assertTrue(runCatching { service.cancel(other, record.id) }.isFailure)
        }
        assertEquals(SubagentStatus.CANCELLED, service.cancel(owner, record.id).status)
    }

    @Test fun `racing dispatches cannot exceed concurrency ceiling`() = runTest {
        val service = SubagentCoordinator(backgroundScope, Store(), StandardTestDispatcher(testScheduler))
        val results = List(20) { async {
            runCatching { service.launch(run(), config.copy(maxConcurrent = 4)) { awaitCancellation() } }
        } }.awaitAll()
        assertEquals(4, results.count { it.isSuccess })
        assertEquals(4, service.list(owner).count { it.status.active })
        service.cancelConversation(owner.conversationId)
        assertTrue(service.list(owner).all { it.status == SubagentStatus.CANCELLED })
    }

    @Test fun `global cap also applies across parent conversations`() = runTest {
        val service = SubagentCoordinator(backgroundScope, Store(), StandardTestDispatcher(testScheduler))
        repeat(8) {
            service.launch(run(scope = owner.copy(conversationId = Uuid.random())), config) { awaitCancellation() }
        }
        assertTrue(runCatching { service.launch(run(), config) { awaitCancellation() } }.isFailure)
    }

    @Test fun `restart marks unfinished work lost without executing it`() = runTest {
        val unfinished = run().copy(status = SubagentStatus.RUNNING)
        val completed = run().copy(status = SubagentStatus.COMPLETED, output = "saved")
        val store = Store(listOf(unfinished, completed))
        val service = SubagentCoordinator(backgroundScope, store, StandardTestDispatcher(testScheduler))
        val records = service.list(owner)
        assertEquals(SubagentStatus.PROCESS_LOST, records.first().status)
        assertEquals("saved", records.last().output)
        assertEquals(records, store.records)
    }

    @Test fun `persistence failure blocks dispatch before work starts`() = runTest {
        val store = Store()
        val service = SubagentCoordinator(backgroundScope, store, StandardTestDispatcher(testScheduler))
        service.list(owner)
        store.failSave = true
        var executed = false
        assertTrue(runCatching { service.launch(run(), config) { executed = true; SubagentOutcome(SubagentStatus.COMPLETED, "bad") } }.isFailure)
        runCurrent()
        assertFalse(executed)
        assertTrue(service.list(owner).isEmpty())
    }

    @Test fun `unreadable ledger fails closed without overwriting existing data`() = runTest {
        var saves = 0
        val store = object : SubagentStore {
            override suspend fun load(): List<SubagentRun> = error("corrupt data")
            override suspend fun save(runs: List<SubagentRun>) { saves++ }
        }
        val service = SubagentCoordinator(backgroundScope, store, StandardTestDispatcher(testScheduler))
        assertTrue(service.list(owner).isEmpty())
        assertNotNull(service.error.value)
        assertTrue(runCatching { service.launch(run(), config) { error("Must not run") } }.isFailure)
        assertEquals(0, saves)
    }

    @Test fun `hourly launch budget and disabled capability reject before execution`() = runTest {
        val store = Store(List(32) { run().copy(status = SubagentStatus.COMPLETED) })
        val service = SubagentCoordinator(backgroundScope, store, StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { service.launch(run(), config) { error("Must not run") } }.isFailure)
        assertTrue(runCatching { service.launch(run(scope = owner.copy(conversationId = Uuid.random())), config.copy(enabled = false)) { error("Must not run") } }.isFailure)
        assertEquals(32, service.list(owner).size)
    }

    @Test fun `terminal save failure is visible and blocks new dispatches`() = runTest {
        val store = Store()
        val service = SubagentCoordinator(backgroundScope, store, StandardTestDispatcher(testScheduler))
        val result = service.launch(run(false), config) {
            store.failSave = true
            SubagentOutcome(SubagentStatus.COMPLETED, "in-memory result")
        }
        assertEquals(SubagentStatus.COMPLETED, result.status)
        assertNotNull(service.error.value)
        assertTrue(runCatching { service.launch(run(), config) { error("Must not run") } }.isFailure)
    }

    @Test fun `cancellation during durable launch never leaves an executable ghost task`() = runTest {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var persisted = emptyList<SubagentRun>()
        val store = object : SubagentStore {
            override suspend fun load() = emptyList<SubagentRun>()
            override suspend fun save(runs: List<SubagentRun>) {
                persisted = runs
                if (runs.any { it.status == SubagentStatus.QUEUED }) { writing.complete(Unit); release.await() }
            }
        }
        val service = SubagentCoordinator(backgroundScope, store, StandardTestDispatcher(testScheduler))
        val record = run()
        var executed = false
        val caller = launch { service.launch(record, config) { executed = true; SubagentOutcome(SubagentStatus.COMPLETED, "unexpected") } }
        writing.await()
        caller.cancel()
        release.complete(Unit)
        caller.join()
        assertFalse(executed)
        assertEquals(SubagentStatus.CANCELLED, service.get(owner, record.id).status)
        assertTrue(persisted.single().parentSuppressed)
    }

    @Test fun `controller revocation is checked again after launch record IO`() = runTest {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var persisted = emptyList<SubagentRun>()
        val store = object : SubagentStore {
            override suspend fun load() = emptyList<SubagentRun>()
            override suspend fun save(runs: List<SubagentRun>) {
                persisted = runs
                if (runs.any { it.status == SubagentStatus.QUEUED }) { writing.complete(Unit); release.await() }
            }
        }
        val service = SubagentCoordinator(backgroundScope, store, StandardTestDispatcher(testScheduler))
        var authorized = true
        var executed = false
        val caller = async { runCatching { service.launch(run(), config, authorize = { check(authorized) }) {
            executed = true; SubagentOutcome(SubagentStatus.COMPLETED, "unexpected")
        } } }
        writing.await(); authorized = false; release.complete(Unit)
        assertTrue(caller.await().isFailure)
        assertFalse(executed)
        assertEquals(SubagentStatus.CANCELLED, persisted.single().status)
    }

    @Test fun `bounded wait does not cancel a background task`() = runTest {
        val service = SubagentCoordinator(backgroundScope, Store(), StandardTestDispatcher(testScheduler))
        val record = service.launch(run(), config) { awaitCancellation() }
        assertTrue(service.wait(owner, record.id, 1).status.active)
        assertEquals(SubagentStatus.CANCELLED, service.cancel(owner, record.id).status)
    }

    @Test fun `clearing history preserves active runs and other assistants`() = runTest {
        val other = owner.copy(assistantId = Uuid.random())
        val store = Store(listOf(run().copy(status = SubagentStatus.COMPLETED), run(scope = other).copy(status = SubagentStatus.COMPLETED)))
        val service = SubagentCoordinator(backgroundScope, store, StandardTestDispatcher(testScheduler))
        val active = service.launch(run(), config) { awaitCancellation() }
        service.clearFinished(owner.assistantId)
        assertEquals(listOf(active.id), service.list(owner).map { it.id })
        assertEquals(1, service.list(other).size)
    }
}
