package me.rerere.rikkahub.subagent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.discoveryPath
import me.rerere.rikkahub.data.ai.tools.discoveryLocation
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.rikkahub.data.ai.tools.searchWorkspaceText
import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.*
import org.junit.Test

class SubagentDiscoveryTest {
    private fun file(path: String, directory: Boolean = false, size: Long = 32) = WorkspaceFileEntry(path.trimStart('/'), path.substringAfterLast('/'), directory, size, 0)

    @Test fun `local workspace bind mount uses file storage rather than the empty rootfs mount point`() {
        val layout = WorkspaceLayout("/workspace", listOf("/workspace"))
        assertEquals(WorkspaceStorageArea.FILES to "src", discoveryLocation(layout, "/workspace/src"))
        assertEquals(WorkspaceStorageArea.LINUX to "usr/include", discoveryLocation(layout, "/usr/include"))
        assertEquals(WorkspaceStorageArea.FILES to "etc", discoveryLocation(WorkspaceLayout("/", listOf("/")), "/etc"))
        assertEquals("/workspace/src", discoveryPath("//workspace/./src/"))
    }

    @Test fun `scout can discover and search code without receiving a shell`() = runTest {
        val tools = listOf("workspace_list_files", "workspace_search", "workspace_shell").map { Tool(it, it, execute = { emptyList() }) }
        assertEquals(listOf("workspace_list_files", "workspace_search"), childTools(tools, SubagentProfile("scout"), null).map { it.name })
        val result = searchWorkspaceText("/repo", "needle", false, list = { path ->
            when (path) {
                "/repo" -> listOf(file("/repo/src", true), file("/repo/node_modules", true))
                "/repo/src" -> listOf(file("/repo/src/a.kt"))
                else -> error("Excluded directory must not be traversed")
            }
        }, read = { "first\nNeedle found\nlast" })
        val match = result["matches"]!!.jsonArray.single().jsonObject
        assertEquals("/repo/src/a.kt", match["path"]!!.jsonPrimitive.content)
        assertEquals(2, match["line"]!!.jsonPrimitive.int)
        assertTrue(result["partial"]!!.jsonPrimitive.boolean)
    }

    @Test fun `search is literal and reports limits rather than pretending to be exhaustive`() = runTest {
        val result = searchWorkspaceText("/repo", "[a-z]+", true,
            list = { listOf(file("/repo/file"), file("/repo/large", size = 1_000_000)) },
            read = { "[a-z]+\n".repeat(100) })
        assertEquals(50, result["matches"]!!.jsonArray.size)
        assertTrue(result["partial"]!!.jsonPrimitive.boolean)
        assertTrue(result.toString().length < 32_000)
    }

    @Test fun `read failures and cancellation are not returned as successful empty searches`() = runTest {
        val result = searchWorkspaceText("/repo", "query", false, list = { listOf(file("/repo/file")) }, read = { error("unreadable") })
        assertTrue(result["partial"]!!.jsonPrimitive.boolean)
        assertTrue(runCatching { searchWorkspaceText("/repo", "query", false,
            list = { throw CancellationException("cancel") }, read = { "" }) }.exceptionOrNull() is CancellationException)
        assertTrue(runCatching { discoveryPath("/repo/../other") }.isFailure)
        assertTrue(runCatching { discoveryPath("relative") }.isFailure)
    }
}
