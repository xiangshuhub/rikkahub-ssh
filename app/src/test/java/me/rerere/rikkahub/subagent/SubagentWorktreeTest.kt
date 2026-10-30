package me.rerere.rikkahub.subagent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

class SubagentWorktreeTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun execute(root: File, command: String): Pair<Int, String> {
        val process = ProcessBuilder("sh", "-c", command).directory(root).redirectErrorStream(true)
            .apply { environment()["GIT_CONFIG_NOSYSTEM"] = "1"; environment()["GIT_CONFIG_GLOBAL"] = "/dev/null" }.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(20, TimeUnit.SECONDS)) { "Fixture Git command timed out" }
        return process.exitValue() to output
    }
    private fun repo(): File {
        val root = temporary.newFolder("repo with spaces")
        File(root, "file.txt").writeText("original\n")
        val (code, output) = execute(root, "git init -q && git config user.name test && git config user.email test@example.invalid && git add file.txt && git -c core.hooksPath=/dev/null commit -qm initial")
        assertEquals(output, 0, code)
        return root
    }
    private fun shell(root: File) = Tool("workspace_shell", "fixture shell", execute = { args ->
        val (exit, output) = execute(root, args.jsonObject["command"]!!.jsonPrimitive.content)
        listOf(UIMessagePart.Text(buildJsonObject { put("exitCode", exit); put("stdout", output); put("stderr", ""); put("timedOut", false) }.toString()))
    })
    private fun run() = SubagentRun(owner = SubagentOwner(Uuid.random(), Uuid.random()), agent = "worker", task = "change file", modelId = Uuid.random(), modelName = "fixture", background = false)

    @Test fun `isolated edits do not touch parent until checked patch apply and untracked files are captured`() = runBlocking {
        val root = repo()
        val manager = SubagentWorktrees()
        val shell = shell(root)
        var intent: SubagentWorktree? = null
        val tree = manager.prepare(run(), shell) { value ->
            intent = value
            assertFalse(File(value.path).exists())
        }
        assertEquals(tree, intent)
        File(tree.path, "file.txt").writeText("child change\n")
        File(tree.path, "new.txt").writeText("new file\n")
        assertEquals("original\n", File(root, "file.txt").readText())
        val patch = manager.capture(tree, shell)
        assertTrue(File(patch.patch!!).readText().contains("new.txt"))
        manager.apply(patch, shell)
        assertEquals("child change\n", File(root, "file.txt").readText())
        assertEquals("new file\n", File(root, "new.txt").readText())
        assertTrue(File(tree.path).exists())
        assertTrue(runCatching { manager.apply(patch, shell) }.isFailure) // No duplicate/forced application.
    }

    @Test fun `dirty parent is rejected without stashing discarding or creating a worktree`() = runBlocking {
        val root = repo()
        File(root, "file.txt").writeText("user edit\n")
        var allocated = false
        assertTrue(runCatching { SubagentWorktrees().prepare(run(), shell(root)) { allocated = true } }.isFailure)
        assertFalse(allocated)
        assertEquals("user edit\n", File(root, "file.txt").readText())
    }

    @Test fun `patch integrity and original HEAD are checked before application`() = runBlocking {
        val root = repo()
        val manager = SubagentWorktrees()
        val shell = shell(root)
        val tree = manager.prepare(run(), shell) {}
        File(tree.path, "file.txt").writeText("child\n")
        val patch = manager.capture(tree, shell)
        File(patch.patch!!).appendText("tampered\n")
        assertTrue(runCatching { manager.apply(patch, shell) }.isFailure)
        assertEquals("original\n", File(root, "file.txt").readText())
    }

    @Test fun `file routing cannot escape by absolute paths or traversal`() {
        val tree = SubagentWorktree("/workspace/repo", "/workspace/repo/.git/rikkahub-subagents/id", "a".repeat(40))
        assertEquals("${tree.path}/src/file.kt", worktreePath(tree, "/workspace/repo/src/file.kt"))
        assertEquals("${tree.path}/src/file.kt", worktreePath(tree, "src/file.kt"))
        assertTrue(runCatching { worktreePath(tree, "../other") }.isFailure)
        assertTrue(runCatching { worktreePath(tree, "/etc/passwd") }.isFailure)
        assertEquals("'a'\\''b'", quote("a'b"))
        assertTrue(runCatching { quote("a\nb") }.isFailure)
    }
}
