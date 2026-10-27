package me.rerere.workspace.ssh

import kotlinx.coroutines.runBlocking
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.shellQuote
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class TmuxLocalIntegrationTest {
    @Test fun isolatedServerCreateListAndGuardedKill() = runBlocking {
        assumeTrue(java.io.File("/usr/bin/tmux").canExecute())
        val directory = Files.createTempDirectory("rikka-tmux-test-").toFile()
        val socket = java.io.File(directory, "socket").path
        fun execute(command: String): WorkspaceCommandResult {
            val isolated = command.replace("tmux -u ", "tmux -u -f /dev/null -S ${socket.shellQuote()} ")
            val process = ProcessBuilder("/bin/sh", "-c", isolated).apply {
                environment()["HOME"] = directory.path
                environment()["SHELL"] = "/bin/sh"
                environment().remove("TMUX")
            }.start()
            check(process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); "tmux test timed out" }
            return WorkspaceCommandResult(process.exitValue(), process.inputStream.bufferedReader().readText(), process.errorStream.bufferedReader().readText())
        }
        try {
            val client = TmuxClient { execute(it) }
            assertTrue(client.sessions().isEmpty())
            val created = client.create("project", directory.path)
            assertEquals(created.id, client.sessions().single().id)
            assertEquals(1, client.windows(created).size)
            assertTrue(runCatching { client.kill(created.copy(created = created.created + 1)) }.isFailure)
            assertEquals(1, client.sessions().size)
            client.kill(created)
            assertTrue(client.sessions().isEmpty())
        } finally {
            execute("env LC_MESSAGES=C tmux -u kill-server")
            directory.deleteRecursively()
        }
    }
}
