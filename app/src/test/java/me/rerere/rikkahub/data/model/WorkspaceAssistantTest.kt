package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class WorkspaceAssistantTest {
    @Test fun `creates ordinary same named assistant using default model and short editable prompt`() {
        val workspaceId = Uuid.random()
        val assistant = createWorkspaceAssistant(workspaceId, "Production Linux")
        assertEquals("Production Linux", assistant.name)
        assertEquals(workspaceId, assistant.workspaceId)
        assertNull(assistant.chatModelId)
        assertEquals(WORKSPACE_ASSISTANT_PROMPT, assistant.systemPrompt)
        assertFalse(assistant.isAppManagementAssistant)
        assertTrue(assistant.mcpServers.isEmpty())
        assertTrue(assistant.enabledSkills.isEmpty())
        assertEquals("My prompt", assistant.copy(systemPrompt = "My prompt").systemPrompt)
    }

    @Test fun `ssh assistant enables only ask user and retains workspace binding`() {
        val workspaceId = Uuid.random()
        val assistant = createWorkspaceAssistant(workspaceId, "Linux")
        assertEquals(listOf(LocalToolOption.AskUser), assistant.localTools)
        assertEquals(workspaceId, assistant.workspaceId)
        assertEquals(emptyList<LocalToolOption>(), assistant.copy(localTools = emptyList()).localTools)
        assertEquals(listOf(LocalToolOption.TimeInfo), Assistant().localTools)
    }

    @Test fun `same names never reuse another assistant identity`() {
        val id = Uuid.random()
        val first = createWorkspaceAssistant(id, "Linux")
        val second = createWorkspaceAssistant(id, "Linux")
        assertNotEquals(first.id, second.id)
        assertEquals(first.workspaceId, second.workspaceId)
    }

    @Test fun `caller may retain stable creation identity for retries without storing environment`() {
        val id = Uuid.random()
        val assistant = createWorkspaceAssistant(Uuid.random(), "Linux", id)
        assertEquals(id, assistant.id)
        assertFalse(assistant.systemPrompt.contains("Ubuntu"))
        assertTrue(assistant.customHeaders.isEmpty())
        assertTrue(assistant.customBodies.isEmpty())
    }
}
