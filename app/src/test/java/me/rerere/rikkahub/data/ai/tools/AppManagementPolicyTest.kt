package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.*
import me.rerere.rikkahub.data.ai.mcp.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class AppManagementPolicyTest {
    @Test fun everyMutationNeedsHumanApproval() {
        listOf("create", "update", "save", "delete", "enable", "", "unknown").forEach { action ->
            assertTrue(managementNeedsApproval(buildJsonObject { put("action", action) }))
        }
        listOf("list", "read", "read_backup", "list_workspaces", "preview_workspace_binding").forEach { action ->
            assertFalse(managementNeedsApproval(buildJsonObject { put("action", action) }))
        }
        assertTrue(managementNeedsApproval(JsonNull))
        assertTrue(managementNeedsApproval(buildJsonObject {}))
    }

    @Test fun validatesSkillNamesAndPreventsPathTraversal() {
        listOf("../secret", "a/b", ".", "CamelCase", "two words", "-start", "x".repeat(65)).forEach {
            assertTrue(runCatching { validateManagedSkillName(it) }.isFailure)
        }
        validateManagedSkillName("server-log-review")
    }

    @Test fun mcpUrlsCannotCarryCredentialsOrExecutableSchemes() {
        listOf("file:///tmp/mcp", "javascript:alert(1)", "https://user:pass@host/mcp", "https://host/mcp?key=secret", "https://host/mcp#token").forEach {
            assertTrue(runCatching { validateManagedMcpUrl(it) }.isFailure)
        }
        assertEquals("http://192.168.1.2:8080/mcp", validateManagedMcpUrl("http://192.168.1.2:8080/mcp"))
    }

    @Test fun staleRevisionsCannotOverwriteConfiguration() {
        requireManagementRevision(managementRevision("old"), managementRevision("old"))
        assertTrue(runCatching { requireManagementRevision(managementRevision("new"), managementRevision("old")) }.isFailure)
    }

    @Test fun existingConversationsCannotBeSilentlyModified() {
        requireNewConversationScope("new_conversations")
        assertTrue(runCatching { requireNewConversationScope("current_conversation") }.isFailure)
        assertTrue(runCatching { requireNewConversationScope("all") }.isFailure)
    }

    @Test fun mcpDeletionRemovesBindingsAndCredentialContainingConfig() {
        val deleted = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "DeleteMe", headers = listOf("Authorization" to "secret"),
                oauth = McpOAuthState(accessToken = "access", refreshToken = "refresh")),
            url = "https://example.com/mcp",
        )
        val kept = McpServerConfig.SseTransportServer(commonOptions = McpCommonOptions(name = "KeepMe"))
        val assistant = Assistant(name = "A", mcpServers = setOf(deleted.id, kept.id), enabledSkills = setOf("keep-skill"))
        val old = Settings(mcpServers = listOf(deleted, kept), assistants = listOf(assistant))
        val updated = removeManagedMcp(old, deleted.id)
        assertEquals(listOf(kept), updated.mcpServers)
        assertEquals(setOf(kept.id), updated.assistants.single().mcpServers)
        assertEquals(setOf("keep-skill"), updated.assistants.single().enabledSkills)
        assertEquals(old, removeManagedMcp(old, Uuid.random()))
    }

    @Test fun mcpListingNeverContainsUrlsHeadersOrOAuthCredentials() {
        val config = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "Server", headers = listOf("Authorization" to "header-secret"),
                oauth = McpOAuthState(clientSecret = "client-secret", accessToken = "access-secret", refreshToken = "refresh-secret")),
            url = "https://user:password@host/mcp?key=url-secret",
        )
        val fields = managedMcpPublicFields(config)
        assertEquals(setOf("id", "name", "enabled", "transport"), fields.keys)
        listOf("secret", "password", "Authorization", "https://").forEach { assertFalse(fields.toString().contains(it)) }
    }
}
