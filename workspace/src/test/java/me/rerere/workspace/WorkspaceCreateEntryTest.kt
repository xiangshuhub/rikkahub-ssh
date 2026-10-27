package me.rerere.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceCreateEntryTest {
    @Test fun validatesSingleNames() {
        listOf("", " ", ".", "..", "../file", "a/b", "a\\b", "a\n", "a\u0000", "x".repeat(256)).forEach {
            assertFalse(it, validWorkspaceEntryName(it))
        }
        listOf("README.md", ".gitignore", "中文 文件夹", "a'b.txt", "-name", "x".repeat(255)).forEach {
            assertTrue(it, validWorkspaceEntryName(it))
        }
        assertFalse(validWorkspaceEntryName("中".repeat(86)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTraversalBeforeExecution() { workspaceCreateEntryCommand("../outside", false) }
}
