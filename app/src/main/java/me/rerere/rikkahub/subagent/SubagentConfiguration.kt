package me.rerere.rikkahub.subagent

import kotlinx.serialization.Serializable
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.isAppManagementAssistant
import kotlin.uuid.Uuid

/** Assistants may narrow global delegation authority, never turn it on against the global switch. */
@Serializable
enum class SubagentMode { INHERIT, DISABLED }

fun Settings.subagentSettingsFor(assistant: Assistant): SubagentSettings {
    val stored = assistants.singleOrNull { it.id == assistant.id }
    val enabled = !init && subagents.enabled && stored != null && !stored.isAppManagementAssistant &&
        stored.subagentMode == SubagentMode.INHERIT
    return if (enabled == subagents.enabled) subagents else subagents.copy(enabled = enabled)
}

fun Settings.subagentSettingsFor(assistantId: Uuid): SubagentSettings? =
    assistants.singleOrNull { it.id == assistantId }?.let { subagentSettingsFor(it) }

/** Legacy values stay in backups but do not silently become global provider/model choices. */
internal fun legacySubagentConfigurations(assistants: List<Assistant>): List<Assistant> = assistants.filter {
    !it.isAppManagementAssistant && it.subagents.copy(enabled = false) != SubagentSettings()
}

internal fun importLegacySubagents(global: SubagentSettings, legacy: SubagentSettings): SubagentSettings =
    legacy.copy(enabled = global.enabled, autoApproveDispatch = global.autoApproveDispatch,
        trustedTools = global.trustedTools).also { it.validate() }
