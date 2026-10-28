package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.normalized
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AppManagementAssistantTest {
    @Test fun defaultsAddManagerWithoutSwitchingCurrentAssistant() {
        val ordinary = Assistant(name = "Worker")
        val settings = Settings(assistants = listOf(ordinary), assistantId = ordinary.id).normalized()
        assertEquals(ordinary.id, settings.assistantId)
        assertEquals(ordinary, settings.assistants.single { it.id == ordinary.id })
        assertEquals(1, settings.assistants.count { it.isAppManagementAssistant })
        assertTrue(settings.assistants.single { it.isAppManagementAssistant }.managementEnabled)
        assertEquals(settings, settings.normalized())
    }

    @Test fun roleFollowsReservedIdentityNotNamePromptOrCopies() {
        val manager = Assistant(id = APP_MANAGEMENT_ASSISTANT_ID, name = "Renamed")
        val settings = Settings(assistants = listOf(manager))
        assertEquals(APP_MANAGEMENT_CAPABILITIES, appManagementCapabilities(manager, settings))
        assertTrue(appManagementCapabilities(manager.copy(id = Uuid.random()), settings).isEmpty())
        assertTrue(appManagementCapabilities(Assistant(name = "应用管理助手"), settings).isEmpty())
        assertTrue(appManagementCapabilities(manager, settings.copy(assistants = emptyList())).isEmpty())
    }

    @Test fun normalizationPreservesDisableAndStripsOtherTools() {
        val original = Assistant(id = APP_MANAGEMENT_ASSISTANT_ID, managementEnabled = false,
            workspaceId = Uuid.random(), enabledSkills = setOf("test"), mcpServers = setOf(Uuid.random()),
            enableWebSearch = true, enableMemory = true, enableRecentChatsReference = true)
        val settings = Settings(assistants = listOf(original)).normalized()
        val manager = settings.assistants.single { it.isAppManagementAssistant }
        assertFalse(manager.managementEnabled)
        assertTrue(appManagementCapabilities(original, settings).isEmpty())
        assertNull(manager.workspaceId)
        assertTrue(manager.localTools.isEmpty())
        assertTrue(manager.mcpServers.isEmpty())
        assertTrue(manager.enabledSkills.isEmpty())
        assertFalse(manager.enableWebSearch)
        assertFalse(manager.enableMemory)
        assertFalse(manager.autoConsolidateMemory)
        assertFalse(manager.enableRecentChatsReference)
    }

    @Test fun managerConversationOverridesCannotAddToolsAndSnapshotIsUnchanged() {
        val manager = Assistant(id = APP_MANAGEMENT_ASSISTANT_ID)
        val config = ConversationConfig(workspaceId = Uuid.random(), enableWebSearch = true,
            enabledSkills = setOf("some-skill"), mcpServers = setOf(Uuid.random()))
        val conversation = Conversation(assistantId = manager.id, config = config, messageNodes = emptyList())
        val effective = Settings(assistants = listOf(manager)).getAssistantOf(conversation)
        assertNull(effective.workspaceId)
        assertFalse(effective.enableWebSearch)
        assertTrue(effective.enabledSkills.isEmpty())
        assertTrue(effective.mcpServers.isEmpty())
        assertEquals(config, conversation.config)
    }

    @Test fun deletedOrdinaryAssistantNeverFallsBackToManager() {
        val manager = Assistant(id = APP_MANAGEMENT_ASSISTANT_ID)
        val settings = Settings(assistants = listOf(manager), assistantId = manager.id)
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())
        assertFalse(settings.getAssistantOf(conversation).isAppManagementAssistant)
    }
}
