package me.rerere.rikkahub.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalPolicyTest {
    @Test
    fun `automatic approval is on by default`() {
        assertTrue(DEFAULT_AUTO_APPROVE_TOOLS)
        assertTrue(shouldAutoApproveTool("workspace_shell", true))
        assertTrue(shouldAutoApproveTool("mcp__server__write", true))
    }

    @Test
    fun `management tools cannot bypass confirmation using global switch`() {
        listOf("app_management_assistants", "app_management_skills", "app_management_mcp").forEach { name ->
            assertFalse(shouldAutoApproveTool(name, true))
            assertFalse(shouldAutoApproveTool(name, false))
        }
    }

    @Test
    fun `questions are never automatically answered`() {
        assertFalse(shouldAutoApproveTool("ask_user", true))
        assertFalse(shouldAutoApproveTool("ask_user", false))
    }

    @Test
    fun `disabled automatic approval leaves original tool policies intact`() {
        listOf("calendar", "clipboard", "mcp__server__write").forEach { name ->
            assertFalse(shouldAutoApproveTool(name, false))
        }
    }

    @Test
    fun `disabled automatic approval leaves workspace scoped policies intact`() {
        assertFalse(shouldAutoApproveTool("workspace_shell", false))
        assertFalse(shouldAutoApproveTool("workspace_edit_file", false))
    }
}
