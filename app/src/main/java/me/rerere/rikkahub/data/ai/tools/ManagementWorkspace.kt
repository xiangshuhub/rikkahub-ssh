package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import kotlin.uuid.Uuid

/** Deliberately excludes sshConfig, addresses, usernames and credential material. */
internal data class ManagementWorkspace(val id: Uuid, val name: String, val type: String, val shellStatus: String, val updatedAt: Long = 0L) {
    fun publicFields() = buildJsonObject {
        put("id", id.toString()); put("name", name); put("type", type)
        put("configured_status", shellStatus)
        put("updated_at", updatedAt)
        put("connectivity_verified", false)
    }
}

internal suspend fun WorkspaceRepository.managementWorkspaceSnapshot(): List<ManagementWorkspace> =
    listFlow().first().map { ManagementWorkspace(Uuid.parse(it.id), it.name, it.type, it.shellStatus, it.updatedAt) }

internal fun parseWorkspaceBinding(value: String): Uuid? = if (value == "none") null else Uuid.parse(value)

internal fun workspaceBindingConfirmation(
    assistantName: String, assistantId: Uuid?, old: Uuid?, target: Uuid?, workspaces: List<ManagementWorkspace>,
): String {
    require(target == null || workspaces.any { it.id == target }) { "Workspace not found; list workspaces again" }
    fun label(id: Uuid?): String = if (id == null) "未绑定" else workspaces.find { it.id == id }?.let {
        "${it.name} [${it.type}] (${it.id}, 配置版本 ${it.updatedAt})"
    } ?: "已不存在的工作空间 ($id)"
    return "助手「$assistantName」(${assistantId ?: "新建"})：${label(old)} → ${label(target)}。仅影响全局配置和新对话，不切换当前对话、不删除工作空间；未验证连接可用性。"
}
