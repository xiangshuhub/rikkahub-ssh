package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

/** Reserved built-in identity, not inferred from a name/prompt or a model-editable role field. */
val APP_MANAGEMENT_ASSISTANT_ID = Uuid.parse("ae5ccf46-e18b-4d25-820a-a19ef1100100")
val Assistant.isAppManagementAssistant: Boolean get() = id == APP_MANAGEMENT_ASSISTANT_ID

internal val APP_MANAGEMENT_CAPABILITIES = setOf("assistants", "workspace_bindings", "skills", "mcp")

internal fun appManagementCapabilities(caller: Assistant, current: Settings): Set<String> =
    if (caller.isAppManagementAssistant &&
        current.assistants.singleOrNull { it.id == caller.id }?.managementEnabled == true
    ) APP_MANAGEMENT_CAPABILITIES else emptySet()

/** Also apply to request snapshots: editing live profiles must never enable extra tools on this role. */
fun Assistant.managementOnly(): Assistant = if (!isAppManagementAssistant) this else copy(
    workspaceId = null,
    localTools = emptyList(),
    enabledSkills = emptySet(),
    mcpServers = emptySet(),
    enableWebSearch = false,
    enableMemory = false,
    useGlobalMemory = false,
    autoConsolidateMemory = false,
    enableRecentChatsReference = false,
    modeInjectionIds = emptySet(),
    lorebookIds = emptySet(),
    enableTimeReminder = false,
    customBodies = emptyList(),
)
