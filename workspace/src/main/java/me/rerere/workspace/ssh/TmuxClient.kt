package me.rerere.workspace.ssh

import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.shellQuote

/** Manual terminal UI only. Uses the SSH user's default tmux socket. */
class TmuxClient(private val execute: suspend (String) -> WorkspaceCommandResult) {
    private val prefix = "env LC_MESSAGES=C tmux -u "

    private suspend fun request(args: String, emptyServer: Boolean = false): String {
        val result = execute(prefix + args)
        check(!result.timedOut && !result.truncated) { "tmux result uncertain; refresh to verify. The operation was not replayed." }
        if (result.exitCode == 0) return result.stdout.trimEnd('\r', '\n')
        val error = result.stderr
        if (emptyServer && result.exitCode == 1 &&
            (error.contains("no server running") || error.contains("no sessions") ||
                (error.contains("error connecting to") && error.contains("No such file or directory")))) return ""
        if (result.exitCode == 127 || error.contains("tmux: not found") || error.contains("tmux: command not found")) {
            throw TmuxUnavailableException()
        }
        error("tmux failed; refresh before retrying: ${error.take(800)}")
    }

    suspend fun sessions(): List<TmuxSession> = parseSessions(request(
        "list-sessions -F ${SESSION_FORMAT.shellQuote()}", emptyServer = true,
    ))

    private fun guarded(session: TmuxSession, command: String): String {
        validate(session)
        val condition = "#{&&:#{==:#{session_created},${session.created}},#{==:#{pid},${session.serverPid}}}"
        return "if-shell -F -t ${session.id.shellQuote()} ${condition.shellQuote()} ${command.shellQuote()} ${"display-message -p RIKKA_TMUX_STALE".shellQuote()}"
    }

    private suspend fun checked(session: TmuxSession, command: String): String {
        val output = request(guarded(session, command))
        check(output.lineSequence().none { it == "RIKKA_TMUX_STALE" }) { "tmux session changed; refresh and select again" }
        return output
    }

    suspend fun windows(session: TmuxSession): List<TmuxWindow> = parseWindows(checked(session,
        "list-windows -t ${session.id.shellQuote()} -F ${WINDOW_FORMAT.shellQuote()}"))

    suspend fun create(name: String, cwd: String): TmuxSession {
        require(validName(name)) { "Use 1–64 letters, numbers, underscores or hyphens" }
        require('\u0000' !in cwd)
        return parseSessions(request("new-session -d -s ${name.shellQuote()} -c ${cwd.shellQuote()} -P -F ${SESSION_FORMAT.shellQuote()}")).single()
    }

    fun attachCommand(session: TmuxSession, window: TmuxWindow): String {
        validate(session)
        require(Regex("@[0-9]{1,10}").matches(window.id))
        val target = "${session.id}:${window.id}"
        val condition = "#{&&:#{==:#{session_created},${session.created}},#{&&:#{==:#{pid},${session.serverPid}},#{==:#{window_id},${window.id}}}}"
        return prefix + "if-shell -F -t ${target.shellQuote()} ${condition.shellQuote()} ${("attach-session -t " + target.shellQuote()).shellQuote()} ${"display-message -p RIKKA_TMUX_STALE".shellQuote()}"
    }

    suspend fun kill(session: TmuxSession) {
        checked(session, "kill-session -t ${session.id.shellQuote()}")
    }

    companion object {
        private const val SESSION_FORMAT = "#{session_id}\t#{session_name}\t#{session_created}\t#{session_windows}\t#{session_attached}\t#{pid}"
        private const val WINDOW_FORMAT = "#{window_id}\t#{window_index}\t#{window_name}\t#{window_active}"
        fun validName(name: String) = Regex("[\\p{L}\\p{N}_-]{1,64}").matches(name)
        private fun validate(session: TmuxSession) {
            require(Regex("\\$[0-9]{1,10}").matches(session.id) && session.created > 0 && session.serverPid > 1)
        }
        fun parseSessions(text: String): List<TmuxSession> = text.lineSequence().filter { it.isNotBlank() }.map {
            val fields = it.split('\t')
            require(fields.size == 6) { "Invalid tmux session response" }
            TmuxSession(fields[0], fields[1], fields[2].toLong(), fields[5].toLong()).also(::validate)
        }.toList()
        fun parseWindows(text: String): List<TmuxWindow> = text.lineSequence().filter { it.isNotBlank() }.map {
            val fields = it.split('\t')
            require(fields.size == 4 && Regex("@[0-9]{1,10}").matches(fields[0])) { "Invalid tmux window response" }
            TmuxWindow(fields[0], fields[2])
        }.toList()
    }
}

data class TmuxSession(val id: String, val name: String, val created: Long, val serverPid: Long)
data class TmuxWindow(val id: String, val name: String)
class TmuxUnavailableException : IllegalStateException("tmux is not installed or not in PATH. Install it on the server, or explicitly choose plain PTY.")
