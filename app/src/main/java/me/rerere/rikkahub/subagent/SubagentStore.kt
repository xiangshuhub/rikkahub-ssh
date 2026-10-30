package me.rerere.rikkahub.subagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal interface SubagentStore {
    suspend fun load(): List<SubagentRun>
    suspend fun save(runs: List<SubagentRun>)
}

/** App-private atomic ledger. No provider configuration is serialized; task/results may themselves be sensitive. */
internal class FileSubagentStore(private val file: File) : SubagentStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override suspend fun load(): List<SubagentRun> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        check(file.length() <= 32 * 1024 * 1024) { "Subagent ledger is too large" }
        json.decodeFromString<List<SubagentRun>>(file.readText())
    }

    override suspend fun save(runs: List<SubagentRun>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        val bytes = json.encodeToString(runs).toByteArray()
        temporary.outputStream().use { it.write(bytes); it.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }
}
