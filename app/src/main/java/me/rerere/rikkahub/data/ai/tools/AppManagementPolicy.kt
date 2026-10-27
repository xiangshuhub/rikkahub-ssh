package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid
import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun managementNeedsApproval(args: JsonElement): Boolean =
    (args as? JsonObject)?.get("action")?.let { (it as? JsonPrimitive)?.contentOrNull } !in setOf("list", "read", "read_backup", "list_workspaces", "preview_workspace_binding")

internal fun removeManagedMcp(settings: Settings, id: Uuid): Settings = settings.copy(
    mcpServers = settings.mcpServers.filterNot { it.id == id },
    assistants = settings.assistants.map { it.copy(mcpServers = it.mcpServers - id) },
)

internal fun managedMcpPublicFields(config: McpServerConfig): JsonObject = buildJsonObject {
    put("id", config.id.toString())
    put("name", config.commonOptions.name)
    put("enabled", config.commonOptions.enable)
    put("transport", if (config is McpServerConfig.SseTransportServer) "sse" else "streamable_http")
}

internal fun managementRevision(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun validateManagementName(name: String) {
    require(name.isNotBlank() && name.length <= 100 && name.none { it.isISOControl() }) { "Invalid name" }
}

internal fun validateManagedSkillName(name: String) {
    require(Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(name) && name.length <= 64) {
        "Skill names must use lowercase letters, digits and hyphens, at most 64 characters"
    }
}

internal fun validateManagedMcpUrl(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: error("MCP URL must be HTTP or HTTPS")
    require(parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query == null && parsed.fragment == null) {
        "Do not put credentials in MCP URLs. Configure authentication manually in settings."
    }
    return parsed.toString()
}

internal fun requireManagementRevision(actual: String, expected: String) {
    check(actual == expected) { "Configuration changed. Read it again and request a new confirmation." }
}

internal fun requireNewConversationScope(scope: String) {
    require(scope == "new_conversations") { "Only scope=new_conversations is supported; existing conversation snapshots are not modified." }
}
