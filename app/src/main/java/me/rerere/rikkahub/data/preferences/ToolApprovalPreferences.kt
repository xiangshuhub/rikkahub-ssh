package me.rerere.rikkahub.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.toolApprovalDataStore by preferencesDataStore(name = "tool_approval")

internal const val DEFAULT_AUTO_APPROVE_TOOLS = true

// Workspace grants remain workspace-scoped. Questions always require a human answer.
internal fun canGrantToolPermanently(name: String): Boolean =
    name != "ask_user" && !name.startsWith("workspace_") && !name.startsWith("app_management_")

internal fun shouldAutoApproveTool(name: String, autoApprove: Boolean, grants: Set<String>): Boolean =
    name != "ask_user" && !name.startsWith("app_management_") &&
        (autoApprove || (canGrantToolPermanently(name) && name in grants))

class ToolApprovalPreferences(context: Context) {
    private val store = context.applicationContext.toolApprovalDataStore
    private val autoApproveKey = booleanPreferencesKey("global_auto_approve_yolo")
    private val grantsKey = stringSetPreferencesKey("always_allow_tool_names")
    private val knownKey = stringSetPreferencesKey("known_tool_names")
    private val managementKey = stringSetPreferencesKey("app_management_permissions")

    val managementPermissionsFlow = store.data.map { it[managementKey].orEmpty() }

    suspend fun managementPermissions(): Set<String> = managementPermissionsFlow.first()

    // Only exposed by the human settings UI, never by a model tool.
    suspend fun setManagementPermission(category: String, enabled: Boolean) {
        require(category in setOf("assistants", "skills", "mcp", "workspace_bindings"))
        store.edit {
            val current = it[managementKey].orEmpty()
            it[managementKey] = if (enabled) current + category else current - category
        }
    }

    val autoApproveFlow = store.data.map { it[autoApproveKey] ?: DEFAULT_AUTO_APPROVE_TOOLS }
    val grantsFlow = store.data.map { it[grantsKey].orEmpty().filterTo(mutableSetOf(), ::canGrantToolPermanently) }
    val knownToolsFlow = store.data.map { it[knownKey].orEmpty().filterTo(mutableSetOf(), ::canGrantToolPermanently) }

    suspend fun snapshot(): Pair<Boolean, Set<String>> {
        val prefs = store.data.first()
        return (prefs[autoApproveKey] ?: DEFAULT_AUTO_APPROVE_TOOLS) to prefs[grantsKey].orEmpty()
    }

    suspend fun setAutoApprove(enabled: Boolean) {
        store.edit { it[autoApproveKey] = enabled }
    }

    suspend fun rememberTools(names: Set<String>) {
        store.edit { it[knownKey] = it[knownKey].orEmpty() + names.filter(::canGrantToolPermanently) }
    }

    suspend fun setGranted(name: String, granted: Boolean) {
        if (!canGrantToolPermanently(name)) return
        store.edit {
            val current = it[grantsKey].orEmpty()
            it[grantsKey] = if (granted) current + name else current - name
        }
    }

    suspend fun revokeAll() {
        store.edit { it.remove(grantsKey) }
    }
}
