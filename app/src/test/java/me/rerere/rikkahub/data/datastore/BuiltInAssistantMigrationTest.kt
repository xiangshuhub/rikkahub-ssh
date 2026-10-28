package me.rerere.rikkahub.data.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import me.rerere.rikkahub.data.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class BuiltInAssistantMigrationTest {
    @Test fun `fresh settings contain only chat and management built ins`() {
        val settings = Settings().normalized()
        assertEquals(setOf(CHAT_ASSISTANT_ID, APP_MANAGEMENT_ASSISTANT_ID), settings.assistants.map { it.id }.toSet())
        assertEquals("聊天助手", settings.getCurrentAssistant().name)
        assertTrue(settings.retiredAssistantProfiles.isEmpty())
        assertEquals(setOf(CHAT_ASSISTANT_ID, APP_MANAGEMENT_ASSISTANT_ID), DEFAULT_ASSISTANTS_IDS.toSet())
    }

    @Test fun `old profiles disappear from selection but remain intact in persisted archives`() {
        val old = RETIRED_ASSISTANT_IDS.map { Assistant(id = it, name = "Custom legacy name", systemPrompt = "My custom prompt", workspaceId = Uuid.random()) }
        val custom = Assistant(name = "My SSH assistant")
        val settings = Settings(assistants = old + custom, assistantId = old.last().id).normalized()
        assertTrue(settings.assistants.none { it.id in RETIRED_ASSISTANT_IDS })
        assertTrue(custom in settings.assistants)
        assertEquals(old, settings.retiredAssistantProfiles)
        assertEquals(CHAT_ASSISTANT_ID, settings.assistantId)
        val prefs = mutablePreferencesOf().apply { putSettings(settings) }
        val restored = prefs.toSettings().normalized()
        assertEquals(old, restored.retiredAssistantProfiles)
        assertEquals(settings.assistants, restored.assistants)
        assertEquals(settings.assistantId, restored.assistantId)
        assertEquals(restored, restored.normalized())
    }

    @Test fun `old conversations keep profile and snapshot rather than adopting manager permissions`() {
        val old = Assistant(id = RETIRED_ASSISTANT_IDS.first(), systemPrompt = "Original customized prompt", workspaceId = Uuid.random())
        val settings = Settings(assistants = listOf(old), assistantId = APP_MANAGEMENT_ASSISTANT_ID).normalized()
        val snapshot = ConversationConfig(workspaceId = Uuid.random())
        val conversation = Conversation(assistantId = old.id, messageNodes = emptyList(), config = snapshot)
        val effective = settings.getAssistantOf(conversation)
        assertEquals(old.systemPrompt, effective.systemPrompt)
        assertEquals(old.id, effective.id)
        assertEquals(snapshot.workspaceId, effective.workspaceId)
        assertFalse(effective.isAppManagementAssistant)
        assertSame(snapshot, conversation.config)
    }

    @Test fun `manager default prompt migrates but custom prompts and disabled state are preserved`() {
        val manager = Assistant(id = APP_MANAGEMENT_ASSISTANT_ID, systemPrompt = LEGACY_APP_MANAGEMENT_PROMPT, managementEnabled = false)
        val migrated = Settings(assistants = listOf(manager)).normalized().getAssistantById(manager.id)!!
        assertEquals(APP_MANAGEMENT_PROMPT, migrated.systemPrompt)
        assertFalse(migrated.managementEnabled)
        val custom = manager.copy(systemPrompt = "User custom management instructions")
        assertEquals(custom.systemPrompt, Settings(assistants = listOf(custom)).normalized().getAssistantById(manager.id)!!.systemPrompt)
    }

    @Test fun `chat history includes retired identities without broadening other assistants`() {
        assertEquals(RETIRED_ASSISTANT_IDS.map { it.toString() }.toSet() + CHAT_ASSISTANT_UUID, assistantHistoryIds(CHAT_ASSISTANT_ID).toSet())
        assertEquals(listOf(APP_MANAGEMENT_ASSISTANT_ID.toString()), assistantHistoryIds(APP_MANAGEMENT_ASSISTANT_ID))
        val other = Uuid.random()
        assertEquals(listOf(other.toString()), assistantHistoryIds(other))
        assertEquals(CHAT_ASSISTANT_ID, visibleAssistantId(RETIRED_ASSISTANT_IDS.first()))
    }

    @Test fun `invalid selection falls back to chat even when manager is first`() {
        val settings = Settings(assistantId = Uuid.random(), assistants = DEFAULT_ASSISTANTS.reversed()).normalized()
        assertEquals(CHAT_ASSISTANT_ID, settings.getCurrentAssistant().id)
    }
}
