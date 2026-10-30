package me.rerere.rikkahub.subagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.Uuid

internal data class SubagentProgress(val output: String, val steps: Int, val inputTokens: Int, val outputTokens: Int, val estimatedCostUsd: Double? = null)

/** Structured foreground cancellation, explicit background lifetime, exact parent ownership. */
internal class SubagentCoordinator(
    private val scope: CoroutineScope,
    private val store: SubagentStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val jobs = mutableMapOf<Uuid, Job>()
    private val replies = mutableMapOf<Uuid, CompletableDeferred<String>>()
    private val _runs = MutableStateFlow<List<SubagentRun>>(emptyList())
    val runs = _runs.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val ready = scope.async(dispatcher) {
        try {
            val recovered = store.load().map {
                if (it.status == SubagentStatus.CANCELLING || it.parentSuppressed && it.status.active) it.copy(status = SubagentStatus.CANCELLED, finishedAt = System.currentTimeMillis())
                else if (it.status.active) it.copy(status = SubagentStatus.PROCESS_LOST,
                    finishedAt = System.currentTimeMillis(), error = "App process ended; this task was not resumed") else it
            }.takeLast(512)
            store.save(recovered)
            _runs.value = recovered
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            _error.value = "无法读取子代理任务记录；为避免丢失记录，已停止启动新任务。"
        }
    }

    suspend fun launch(
        run: SubagentRun,
        settings: SubagentSettings,
        resume: Boolean = false,
        authorize: () -> Unit = {},
        work: suspend (suspend (SubagentProgress) -> Unit) -> SubagentOutcome,
    ): SubagentRun {
        ready.await()
        settings.validate()
        require(settings.enabled) { "Subagents are disabled" }
        require(run.task.isNotBlank() && run.task.length <= SUBAGENT_TASK_LIMIT) { "Task must contain 1–8192 characters" }
        val caller = currentCoroutineContext()
        var registered = false
        try {
            lock.withLock {
                caller.ensureActive()
                withContext(NonCancellable) {
            authorize()
            check(_error.value == null) { "Subagent persistence is unavailable" }
            if (jobs.values.count { !it.isCompleted } >= 8) throw SubagentCapacityException("Global subagent concurrency limit (8) reached")
            if (_runs.value.count { it.owner == run.owner && jobs[it.id]?.isCompleted == false } >= settings.maxConcurrent) throw SubagentCapacityException("Parent conversation concurrency limit reached")
            check(resume || _runs.value.count { it.owner == run.owner && System.currentTimeMillis() - it.createdAt < 3_600_000 } < 32) { "Parent conversation hourly launch budget (32) reached" }
            require(run.depth in 1..settings.maxDepth) { "Child depth limit reached" }
            if (run.parentRunId != null && !resume) {
                val parent = _runs.value.singleOrNull { it.id == run.parentRunId && it.owner == run.owner && it.status.active && !it.parentSuppressed }
                    ?: error("Parent run is no longer active")
                require(run.depth == parent.depth + 1) { "Invalid child depth" }
            } else if (run.parentRunId == null) require(run.depth == 1)
            val old = _runs.value.singleOrNull { it.id == run.id }
            if (resume) require(old != null && old.owner == run.owner && !old.status.active && old.attempts < 3) { "Run cannot be resumed" }
            else check(old == null) { "Duplicate run id" }
            val records = retain(_runs.value.filterNot { it.id == run.id } + run.copy(status = SubagentStatus.QUEUED, finishedAt = null, error = null, parentSuppressed = false, startEpoch = nextEpoch(),
                attempts = if (resume) old!!.attempts + 1 else run.attempts))
            store.save(records) // No model request before its launch record is durable.
            _runs.value = records
            registered = true
            val authorizationFailure = try { authorize(); null } catch (e: Exception) { e }
            if (!caller.isActive || authorizationFailure != null) {
                val cancelled = records.map { if (it.id == run.id) it.copy(status = SubagentStatus.CANCELLED,
                    parentSuppressed = true, cancelEpoch = nextEpoch(), finishedAt = System.currentTimeMillis()) else it }
                _runs.value = cancelled
                persistSafely(cancelled)
                if (authorizationFailure != null) throw authorizationFailure
                return@withContext
            }
            val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
                var outcome = SubagentOutcome(SubagentStatus.CANCELLED, "", "Task cancelled")
                try {
                    update(run.id, persist = true) { it.copy(status = SubagentStatus.RUNNING) }
                    outcome = withTimeout(settings.timeoutSeconds * 1000L) {
                        work { progress ->
                            update(run.id, persist = false) {
                                it.copy(output = boundedSubagentText(progress.output), steps = progress.steps,
                                    inputTokens = progress.inputTokens, outputTokens = progress.outputTokens, estimatedCostUsd = progress.estimatedCostUsd)
                            }
                        }
                    }
                    require(!outcome.status.active) { "Invalid terminal outcome" }
                } catch (e: SubagentBudgetExceeded) {
                    outcome = SubagentOutcome(SubagentStatus.BUDGET_LIMIT, "", "Usage budget reached; current request may already have incurred usage")
                } catch (e: TimeoutCancellationException) {
                    outcome = SubagentOutcome(SubagentStatus.TIMED_OUT, "", "Task deadline reached")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SubagentInfrastructureException) {
                    outcome = SubagentOutcome(SubagentStatus.FAILED, "", e.message)
                } catch (e: Exception) {
                    // Providers sometimes include auth/response bodies in exceptions. Do not persist those.
                    outcome = SubagentOutcome(SubagentStatus.FAILED, "", "Task failed (${e.javaClass.simpleName}); check model and tool configuration")
                } finally {
                    withContext(NonCancellable) {
                        if (outcome.status != SubagentStatus.COMPLETED) {
                            _runs.value.filter { it.parentRunId == run.id && it.status.active }.forEach { cancel(it.owner, it.id) }
                        }
                        lock.withLock {
                            val records = _runs.value.map { existing ->
                                if (existing.id != run.id || !existing.status.active) existing else existing.copy(
                                    status = if (existing.status == SubagentStatus.CANCELLING) SubagentStatus.CANCELLED else outcome.status,
                                    output = boundedSubagentText(outcome.output.ifBlank { existing.output }),
                                    error = if (existing.status == SubagentStatus.CANCELLING) "Cancelled by parent or user" else outcome.error?.take(2048),
                                    finishedAt = System.currentTimeMillis(), eventRevision = existing.eventRevision + 1)
                            }
                            _runs.value = records
                            jobs.remove(run.id)
                            replies.remove(run.id)?.cancel()
                            persistSafely(records)
                        }
                    }
                }
            }
            jobs[run.id] = job
            job.invokeOnCompletion {
                scope.launch { lock.withLock { jobs.remove(run.id, job) } }
            }
            job.start()
                }
            }
            caller.ensureActive()
            if (run.background) return get(run.owner, run.id)
            return wait(run.owner, run.id, settings.timeoutSeconds + 5)
        } catch (e: CancellationException) {
            if (registered) withContext(NonCancellable) { cancel(run.owner, run.id) }
            throw e
        }
    }

    private fun nextEpoch(): Long {
        val latest = _runs.value.maxOfOrNull { maxOf(it.startEpoch, it.cancelEpoch) } ?: 0L
        check(latest < Long.MAX_VALUE) { "Invalid task lifecycle sequence" }
        return latest + 1
    }

    private fun retain(records: List<SubagentRun>): List<SubagentRun> {
        val activeGroups = records.filter { it.status.active }.map { it.budgetGroupId ?: it.id }.toSet()
        val terminal = records.filter { !it.status.active }.takeLast(120).map { it.id }.toSet()
        return records.filter { it.status.active || it.id in terminal || (it.budgetGroupId ?: it.id) in activeGroups }
    }

    private suspend fun update(id: Uuid, persist: Boolean, change: (SubagentRun) -> SubagentRun) = lock.withLock {
        val records = _runs.value.map { if (it.id == id && it.status.active) change(it) else it }
        if (persist) store.save(records)
        _runs.value = records
    }

    private suspend fun persistSafely(records: List<SubagentRun>) {
        try { store.save(records) } catch (e: Exception) {
            _error.value = "子代理任务记录保存失败；当前状态仅在内存中，禁止启动新任务。"
        }
    }

    suspend fun retainedIds(): Set<Uuid> {
        ready.await()
        check(_error.value == null) { "Ledger unavailable" }
        return _runs.value.map { it.id }.toSet()
    }

    suspend fun list(owner: SubagentOwner): List<SubagentRun> {
        ready.await()
        return _runs.value.filter { it.owner == owner }
    }

    suspend fun get(owner: SubagentOwner, id: Uuid): SubagentRun = list(owner).find { it.id == id }
        ?: error("Unknown run in this parent conversation")

    suspend fun wait(owner: SubagentOwner, id: Uuid, seconds: Int = 60): SubagentRun {
        get(owner, id) // Ownership is checked before looking up the job.
        withTimeoutOrNull(seconds.coerceIn(1, 1805) * 1000L) {
            runs.first { all -> all.any { it.id == id && (!it.status.active || it.status == SubagentStatus.WAITING_INPUT) } }
        }
        return get(owner, id)
    }

    suspend fun cancel(owner: SubagentOwner, id: Uuid): SubagentRun {
        get(owner, id)
        val job = lock.withLock {
            val job = jobs[id]
            val epoch = nextEpoch()
            _runs.value = _runs.value.map {
                if (it.id != id) it else it.copy(parentSuppressed = true, cancelEpoch = epoch,
                    status = if (it.status.active) SubagentStatus.CANCELLING else if (it.status == SubagentStatus.PROCESS_LOST) SubagentStatus.CANCELLED else it.status,
                    error = if (it.status.active) "Cancellation requested" else it.error)
            }
            job?.cancel()
            persistSafely(_runs.value)
            job
        }
        withContext(NonCancellable) {
            _runs.value.filter { it.parentRunId == id && it.status.active }.forEach { cancel(it.owner, it.id) }
            job?.join()
            // A lazy job cancelled before its body starts has no finally block to settle the record.
            lock.withLock {
                val records = _runs.value.map {
                    if (it.id == id && it.status == SubagentStatus.CANCELLING) it.copy(status = SubagentStatus.CANCELLED,
                        finishedAt = System.currentTimeMillis(), error = "Cancelled by parent or user") else it
                }
                if (records != _runs.value) {
                    _runs.value = records
                    persistSafely(records)
                }
            }
        }
        return get(owner, id)
    }

    suspend fun account(id: Uuid, config: SubagentSettings, progress: SubagentProgress, reservation: Boolean = false) = lock.withLock {
        val current = _runs.value.single { it.id == id && it.status.active }
        val group = current.budgetGroupId ?: current.id
        val records = _runs.value.map { if (it.id == id) it.copy(inputTokens = progress.inputTokens,
            outputTokens = progress.outputTokens, estimatedCostUsd = progress.estimatedCostUsd) else it }
        val members = records.filter { (it.budgetGroupId ?: it.id) == group && it.owner == current.owner }
        val exceeded = members.sumOf { it.inputTokens.toLong() + it.outputTokens } > config.maxTotalTokens ||
            config.maxCostUsd?.let { limit -> members.sumOf { it.estimatedCostUsd ?: 0.0 } > limit } == true
        if (reservation && exceeded) throw SubagentBudgetExceeded()
        store.save(records)
        _runs.value = records
        if (exceeded) throw SubagentBudgetExceeded()
    }

    suspend fun ask(id: Uuid, key: String, question: String): String {
        require(question.isNotBlank() && question.length <= 8192)
        val waiting = lock.withLock {
            val run = _runs.value.single { it.id == id && it.status.active }
            if (run.questionKey == key && run.answer != null) return run.answer
            val answer = CompletableDeferred<String>()
            replies[id] = answer
            val records = _runs.value.map { if (it.id == id) it.copy(status = SubagentStatus.WAITING_INPUT,
                questionKey = key, question = question, answer = null, eventRevision = it.eventRevision + 1) else it }
            store.save(records)
            _runs.value = records
            answer
        }
        return try { waiting.await() } finally { lock.withLock { replies.remove(id, waiting) } }
    }

    suspend fun reply(owner: SubagentOwner, id: Uuid, key: String, answer: String): SubagentRun {
        require(answer.isNotBlank() && answer.length <= 8192)
        get(owner, id)
        lock.withLock {
            val run = _runs.value.single { it.id == id }
            check(run.status == SubagentStatus.WAITING_INPUT && run.questionKey == key && run.answer == null) { "Question is no longer pending" }
            val waiter = replies[id] ?: error("Task must be resumed before replying")
            val records = _runs.value.map { if (it.id == id) it.copy(status = SubagentStatus.RUNNING, answer = answer,
                deliveredRevision = it.eventRevision) else it }
            store.save(records)
            _runs.value = records
            waiter.complete(answer)
        }
        return get(owner, id)
    }

    suspend fun observed(owner: SubagentOwner, id: Uuid): Boolean {
        get(owner, id)
        return lock.withLock {
            val run = _runs.value.single { it.id == id }
            if (run.deliveredRevision >= run.eventRevision) return@withLock false
            val records = _runs.value.map { if (it.id == id) it.copy(deliveredRevision = it.eventRevision) else it }
            store.save(records)
            _runs.value = records
            true
        }
    }

    suspend fun claimParentWake(owner: SubagentOwner, ids: Set<Uuid>): Boolean = lock.withLock {
        val now = System.currentTimeMillis()
        require(ids.isNotEmpty() && _runs.value.count { it.owner == owner && it.id in ids } == ids.size)
        val count = _runs.value.filter { it.owner == owner }.flatMap { it.parentWakeHistory }.count { now - it < 3_600_000 }
        if (count >= 4) return@withLock false
        val charged = ids.first()
        val records = _runs.value.map { if (it.owner == owner && it.id in ids) it.copy(parentWakeAt = now,
            parentWakeHistory = if (it.id == charged) (it.parentWakeHistory.filter { at -> now - at < 3_600_000 } + now).takeLast(4) else it.parentWakeHistory) else it }
        store.save(records); _runs.value = records
        true
    }

    suspend fun setWorktree(id: Uuid, worktree: SubagentWorktree) = lock.withLock {
        val records = _runs.value.map { if (it.id == id) it.copy(worktree = worktree) else it }
        store.save(records); _runs.value = records
    }

    suspend fun markRecoveryBlocked(owner: SubagentOwner, id: Uuid) = withContext(NonCancellable) { lock.withLock {
        val records = _runs.value.map { if (it.id == id && it.owner == owner && it.status == SubagentStatus.PROCESS_LOST && !it.parentSuppressed)
            it.copy(status = SubagentStatus.BLOCKED, error = "Automatic recovery could not validate a checkpoint, model or permission; inspect the task and resume explicitly", eventRevision = it.eventRevision + 1) else it }
        if (records != _runs.value) { _runs.value = records; persistSafely(records) }
    } }

    suspend fun markUncertain(owner: SubagentOwner, id: Uuid) {
        get(owner, id)
        lock.withLock {
            val records = _runs.value.map { if (it.id == id && !it.status.active) it.copy(status = SubagentStatus.UNCERTAIN,
                error = "Tool execution may have completed before process loss; it will not be replayed", eventRevision = it.eventRevision + 1) else it }
            store.save(records); _runs.value = records
        }
    }

    suspend fun cancelConversation(id: Uuid) {
        ready.await()
        lock.withLock {
            val targets = _runs.value.filter { it.owner.conversationId == id }.map { it.id }.toSet()
            if (targets.isNotEmpty()) {
                val epoch = nextEpoch()
                targets.forEach { jobs[it]?.cancel() }
                _runs.value = _runs.value.map { if (it.id in targets) it.copy(parentSuppressed = true, cancelEpoch = epoch,
                    status = if (it.status.active) SubagentStatus.CANCELLING else it.status) else it }
                persistSafely(_runs.value)
            }
        }
        _runs.value.filter { it.owner.conversationId == id && it.status.active }.forEach { cancel(it.owner, it.id) }
    }

    suspend fun clearFinished(assistantId: Uuid) {
        ready.await()
        lock.withLock {
            val activeGroups = _runs.value.filter { it.status.active }.map { it.budgetGroupId ?: it.id }.toSet()
            val records = _runs.value.filterNot { it.owner.assistantId == assistantId && !it.status.active && (it.budgetGroupId ?: it.id) !in activeGroups }
            store.save(records)
            _runs.value = records
        }
    }
}

internal fun boundedSubagentText(text: String): String = if (text.length <= SUBAGENT_OUTPUT_LIMIT) text
    else text.take(SUBAGENT_OUTPUT_LIMIT - 32) + "\n[Subagent output truncated]"
