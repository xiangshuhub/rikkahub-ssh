package me.rerere.rikkahub.subagent

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.serialization.encodeToString
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.putSettings
import me.rerere.rikkahub.data.datastore.toSettings
import me.rerere.rikkahub.data.model.APP_MANAGEMENT_ASSISTANT_ID
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class SubagentConfigurationTest {
    private val a = Assistant(name = "A", workspaceId = Uuid.random(), mcpServers = setOf(Uuid.random()))
    private val b = Assistant(name = "B", workspaceId = Uuid.random(), mcpServers = setOf(Uuid.random()))
    private fun settings() = Settings(assistants = listOf(a, b))

    @Test fun `global defaults are enabled and trusted for every ordinary assistant`() {
        val settings = settings()
        assertTrue(settings.subagents.enabled)
        assertTrue(settings.subagents.autoApproveDispatch)
        assertTrue(settings.subagents.trustedTools)
        assertEquals(SubagentMode.INHERIT, a.subagentMode)
        assertEquals(settings.subagents, settings.subagentSettingsFor(a))
        assertEquals(settings.subagents, settings.subagentSettingsFor(b))
        assertNotEquals(a.workspaceId, b.workspaceId)
        assertNotEquals(a.mcpServers, b.mcpServers)
        assertEquals(listOf(a, b), settings.assistants) // Resolving global config never merges parent capabilities.
    }

    @Test fun `assistant disable narrows global authority and cannot force enable against global off`() {
        val disabled = a.copy(subagentMode = SubagentMode.DISABLED, subagents = SubagentSettings(enabled = true))
        val settings = settings().copy(assistants = listOf(disabled, b))
        assertFalse(settings.subagentSettingsFor(disabled).enabled)
        assertTrue(settings.subagentSettingsFor(b).enabled)
        val off = settings.copy(subagents = settings.subagents.copy(enabled = false))
        assertFalse(off.subagentSettingsFor(b).enabled)
        assertFalse(off.subagentSettingsFor(disabled).enabled)
    }

    @Test fun `manager deleted profiles and loading defaults never acquire global delegation`() {
        val manager = Assistant(id = APP_MANAGEMENT_ASSISTANT_ID, subagents = SubagentSettings(enabled = true))
        val settings = settings().copy(assistants = listOf(a, manager))
        assertFalse(settings.subagentSettingsFor(manager).enabled)
        assertFalse(settings.subagentSettingsFor(b).enabled)
        assertNull(settings.subagentSettingsFor(b.id))
        assertFalse(settings.copy(init = true).subagentSettingsFor(a).enabled)
    }

    @Test fun `global changes immediately govern task permissions across assistants`() {
        val settings = settings()
        fun run(assistant: Assistant) = SubagentRun(owner = SubagentOwner(assistant.id, Uuid.random()), agent = "worker",
            task = "task", modelId = Uuid.random(), modelName = "fake", background = true)
        val runs = listOf(run(a), run(b))
        assertTrue(runs.all { isSubagentRoleChainEnabled(it, runs, settings) })
        val off = settings.copy(subagents = settings.subagents.copy(enabled = false))
        assertTrue(runs.none { isSubagentRoleChainEnabled(it, runs, off) })
        val roleDisabled = settings.copy(subagents = settings.subagents.copy(profiles = settings.subagents.profiles.map {
            if (it.name == "worker") it.copy(enabled = false) else it
        }))
        assertTrue(runs.none { isSubagentRoleChainEnabled(it, runs, roleDisabled) })
        val onlyAOff = settings.copy(assistants = listOf(a.copy(subagentMode = SubagentMode.DISABLED), b))
        assertFalse(isSubagentRoleChainEnabled(runs[0], runs, onlyAOff))
        assertTrue(isSubagentRoleChainEnabled(runs[1], runs, onlyAOff))
    }

    @Test fun `legacy assistant config is retained but never shadows the global source of truth`() {
        val legacy = SubagentSettings(enabled = false, maxSteps = 6, profiles = listOf(SubagentProfile("private", modelId = Uuid.random())))
        val oldAssistant = a.copy(subagents = legacy)
        val global = settings().copy(assistants = listOf(oldAssistant, b))
        assertEquals(global.subagents, global.subagentSettingsFor(oldAssistant))
        assertEquals(legacy, global.assistants.first().subagents)
        assertEquals(listOf(oldAssistant), legacySubagentConfigurations(listOf(oldAssistant, b)))
        val restored = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(global))
        assertEquals(legacy, restored.assistants.first().subagents)
        assertEquals(global.subagents, restored.subagentSettingsFor(oldAssistant))
    }

    @Test fun `legacy import preserves global consent switches and custom roles`() {
        val legacy = SubagentSettings(enabled = true, autoApproveDispatch = false, trustedTools = false,
            maxSteps = 6, profiles = listOf(SubagentProfile("custom", systemPrompt = "keep this", modelId = Uuid.random())))
        val imported = importLegacySubagents(settings().subagents, legacy)
        assertTrue(imported.enabled)
        assertTrue(imported.autoApproveDispatch)
        assertTrue(imported.trustedTools)
        assertEquals(legacy.profiles, imported.profiles)
        assertEquals(6, imported.maxSteps)
        val off = importLegacySubagents(settings().subagents.copy(enabled = false, autoApproveDispatch = false, trustedTools = false), legacy)
        assertFalse(off.enabled)
        assertFalse(off.autoApproveDispatch)
        assertFalse(off.trustedTools)
    }

    @Test fun `global config and assistant override survive preferences and backup round trips`() {
        val custom = settings().copy(subagents = settings().subagents.copy(enabled = false, maxConcurrent = 2,
            profiles = listOf(SubagentProfile("custom", modelId = Uuid.random(), systemPrompt = "global prompt"))),
            assistants = listOf(a.copy(subagentMode = SubagentMode.DISABLED), b))
        val preferences = mutablePreferencesOf().apply { putSettings(custom) }
        assertNotNull(preferences[SettingsStore.SUBAGENT_SETTINGS])
        val restoredPreferences = preferences.toSettings()
        val restoredBackup = JsonInstant.decodeFromString<Settings>(JsonInstant.encodeToString(custom))
        // Built-in provider UI lambdas are transient; compare the persisted delegation state.
        assertEquals(custom.subagents, restoredPreferences.subagents)
        assertEquals(custom.assistants, restoredPreferences.assistants)
        assertEquals(custom.subagents, restoredBackup.subagents)
        assertEquals(custom.assistants, restoredBackup.assistants)
        val rawAssistants = preferences[SettingsStore.ASSISTANTS]
        preferences.putSettings(custom.copy(subagents = custom.subagents.copy(enabled = true)), persisted = custom)
        assertEquals(rawAssistants, preferences[SettingsStore.ASSISTANTS])
        assertTrue(preferences.toSettings().subagents.enabled)
        assertEquals(SubagentMode.DISABLED, preferences.toSettings().assistants.first().subagentMode)
        assertTrue(emptyPreferences().toSettings().subagents.enabled)
    }

    @Test fun `shared role without fixed model still inherits each parents actual model`() {
        val modelA = Model(modelId = "parent-a")
        val modelB = Model(modelId = "parent-b")
        val settings = settings().copy(providers = listOf(ProviderSetting.OpenAI(models = listOf(modelA, modelB))))
        val profile = settings.subagents.profiles.first()
        assertEquals(modelA, resolveSubagentModel(settings, modelA, profile, null))
        assertEquals(modelB, resolveSubagentModel(settings, modelB, profile, null))
    }
}
