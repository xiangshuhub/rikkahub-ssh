package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ManagementSshToolsTest {
    private val id = Uuid.random().toString()
    private fun args(target: String = id) = buildJsonObject { put("workspace_id", target); put("command", "uname -m"); put("cwd", "project") }

    @Test fun `construction does not connect and execution routes to exactly the requested target`() = runTest {
        var resolutions = 0
        val tools = createManagementSshTools(true, emptyMap(), { true }) { selected, name ->
            resolutions++
            assertEquals(id, selected)
            assertEquals("workspace_shell", name)
            Tool(name, "test", execute = {
                assertNull(it.jsonObject["workspace_id"])
                assertEquals("uname -m", it.jsonObject["command"]!!.jsonPrimitive.content)
                assertEquals("project", it.jsonObject["cwd"]!!.jsonPrimitive.content)
                listOf(UIMessagePart.Text("x86_64"))
            })
        }
        assertEquals(0, resolutions)
        val result = tools.single { it.name == "management_ssh_shell" }.execute(args())
        assertEquals(1, resolutions)
        assertTrue((result.first() as UIMessagePart.Text).text.contains(id))
        assertEquals("x86_64", (result.last() as UIMessagePart.Text).text)
    }

    @Test fun `missing or malformed workspace never falls back to another target`() = runTest {
        val tools = createManagementSshTools(true, emptyMap(), { true }) { _, _ -> error("Should not resolve") }
        val shell = tools.last()
        for (input in listOf(buildJsonObject { put("command", "pwd") }, args("hostname-not-uuid"))) {
            assertTrue(runCatching { shell.execute(input) }.isFailure)
        }
    }

    @Test fun `revocation blocks already created tools and is rechecked after resolution`() = runTest {
        var enabled = true
        var ran = false
        val tools = createManagementSshTools(true, emptyMap(), { enabled }) { _, name ->
            enabled = false
            Tool(name, "test", execute = { ran = true; emptyList() })
        }
        assertTrue(runCatching { tools.last().execute(args()) }.isFailure)
        assertFalse(ran)
        val revoked = createManagementSshTools(true, emptyMap(), { false }) { _, _ -> error("Should not resolve") }
        assertTrue(runCatching { revoked.first().execute(args()) }.isFailure)
    }

    @Test fun `approval follows global switch and selected workspace overrides`() {
        val resolver: suspend (String, String) -> Tool = { _, name -> Tool(name, "test", execute = { emptyList() }) }
        val overrides = mapOf(id to emptyMap<String, Boolean>())
        val auto = createManagementSshTools(true, overrides, { true }, resolver)
        assertFalse(auto.last().needsApproval(args()))
        val manual = createManagementSshTools(false, overrides, { true }, resolver)
        assertTrue(manual.last().needsApproval(args()))
        assertFalse(manual.first().needsApproval(args()))
        val granted = createManagementSshTools(false, mapOf(id to mapOf("workspace_shell" to false)), { true }, resolver)
        assertFalse(granted.last().needsApproval(args()))
        assertTrue(granted.last().needsApproval(args(Uuid.random().toString())))
    }

    @Test fun `resolver failures do not invoke another workspace or tool`() = runTest {
        var calls = 0
        val tools = createManagementSshTools(true, emptyMap(), { true }) { _, _ ->
            calls++
            error("Workspace missing, not SSH, or not ready")
        }
        assertTrue(runCatching { tools.first().execute(buildJsonObject { put("workspace_id", id); put("path", "/project/README.md") }) }.isFailure)
        assertEquals(1, calls)
        assertEquals(setOf("management_ssh_read_file", "management_ssh_shell"), tools.map { it.name }.toSet())
    }
}
