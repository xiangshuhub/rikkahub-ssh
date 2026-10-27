package me.rerere.rikkahub.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalPolicyTest {
    @Test
    fun `automatic approval is on by default`() {
        assertTrue(DEFAULT_AUTO_APPROVE_TOOLS)
        assertTrue(shouldAutoApproveTool("workspace_shell", true, emptySet()))
        assertTrue(shouldAutoApproveTool("mcp__server__write", true, emptySet()))
    }

    @Test
    fun `management tools cannot bypass confirmation using global or individual grants`() {
        listOf("app_management_assistants", "app_management_skills", "app_management_mcp").forEach { name ->
            assertFalse(canGrantToolPermanently(name))
            assertFalse(shouldAutoApproveTool(name, true, setOf(name)))
            assertFalse(shouldAutoApproveTool(name, false, setOf(name)))
        }
    }

    @Test
    fun `questions are never automatically answered`() {
        assertFalse(shouldAutoApproveTool("ask_user", true, setOf("ask_user")))
        assertFalse(canGrantToolPermanently("ask_user"))
    }

    @Test
    fun `disabled automatic approval respects individual grants`() {
        assertFalse(shouldAutoApproveTool("calendar", false, emptySet()))
        assertTrue(shouldAutoApproveTool("calendar", false, setOf("calendar")))
        assertFalse(shouldAutoApproveTool("calendar", false, setOf("other")))
    }

    @Test
    fun `workspace grants cannot leak between workspaces`() {
        assertFalse(canGrantToolPermanently("workspace_shell"))
        assertFalse(shouldAutoApproveTool("workspace_shell", false, setOf("workspace_shell")))
    }
}
