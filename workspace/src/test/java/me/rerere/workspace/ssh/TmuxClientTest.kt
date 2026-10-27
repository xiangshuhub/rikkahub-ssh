package me.rerere.workspace.ssh

import kotlinx.coroutines.runBlocking
import me.rerere.workspace.WorkspaceCommandResult
import org.junit.Assert.*
import org.junit.Test

class TmuxClientTest {
    @Test fun parsesSessionAndWindowIds() {
        assertEquals("\$1", TmuxClient.parseSessions("\$1\tproject\t123\t1\t0\t456").single().id)
        assertEquals("@2", TmuxClient.parseWindows("@2\t0\tshell\t1").single().id)
        assertTrue(TmuxClient.validName("项目-test_1"))
        assertFalse(TmuxClient.validName("a; reboot"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedSession() { TmuxClient.parseSessions("bad\tname\t123\t1\t0\t456") }

    @Test fun attachChecksIdentityAndDoesNotKillOtherClients() {
        val client = TmuxClient { error("not called") }
        val command = client.attachCommand(TmuxSession("\$1", "project", 123, 456), TmuxWindow("@2", "shell"))
        assertTrue(command.contains("session_created"))
        assertTrue(command.contains("window_id"))
        assertTrue(command.contains("RIKKA_TMUX_STALE"))
        assertFalse(command.contains("attach-session -d"))
    }

    @Test fun absentServerIsEmptyButOtherErrorsAreNot() = runBlocking {
        val client = TmuxClient { WorkspaceCommandResult(1, "", "no server running on /tmp/tmux/default") }
        assertTrue(client.sessions().isEmpty())
        val failing = TmuxClient { WorkspaceCommandResult(1, "", "Permission denied") }
        assertTrue(runCatching { failing.sessions() }.isFailure)
    }

    @Test fun uncertainCreateIsNeverReplayed() = runBlocking {
        var calls = 0
        val client = TmuxClient { calls++; WorkspaceCommandResult(-1, "", "timeout", timedOut = true) }
        assertTrue(runCatching { client.create("project", "/tmp/a ' b") }.isFailure)
        assertEquals(1, calls)
    }
}
