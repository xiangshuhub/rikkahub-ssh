package me.rerere.rikkahub.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.toolApprovalDataStore by preferencesDataStore(name = "tool_approval")

internal const val DEFAULT_AUTO_APPROVE_TOOLS = true

// Questions and management mutations must never be bypassed by the ordinary-tool switch.
internal fun shouldAutoApproveTool(name: String, autoApprove: Boolean): Boolean =
    autoApprove && name != "ask_user" && !name.startsWith("app_management_")

class ToolApprovalPreferences(context: Context) {
    private val store = context.applicationContext.toolApprovalDataStore
    private val autoApproveKey = booleanPreferencesKey("global_auto_approve_yolo")
    // Legacy always_allow_tool_names, known_tool_names and app_management_permissions are ignored.
    // Workspace-scoped approval rules are managed separately and remain unchanged.

    val autoApproveFlow = store.data.map { it[autoApproveKey] ?: DEFAULT_AUTO_APPROVE_TOOLS }

    suspend fun snapshot(): Boolean = autoApproveFlow.first()

    suspend fun setAutoApprove(enabled: Boolean) {
        store.edit { it[autoApproveKey] = enabled }
    }
}
