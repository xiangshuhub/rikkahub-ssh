# SSH / tmux migration from rikkahub-ssh

## Implemented

- Chat input shortcuts: `>_` opens the bound workspace terminal; `文件` opens the workspace Files tab.
- No second host database or credential store. Uses 3.1.1 workspace SSH settings, trusted host keys, encrypted credentials and connection pool.
- With no binding, shortcuts show a message and open workspace management. Bind the workspace in assistant settings before using the shortcuts.
- SSH terminal initially offers tmux discovery/window selection, explicit session creation, refresh, guarded session termination, or explicit plain PTY fallback. No automatic installation or mutation replay.
- New SSH sessions use the conversation directory when provided. Existing live terminal tabs are retained without sending `cd` or other input to their running programs. Existing tmux windows retain their own working directory.
- tmux attachment validates server PID, session creation time and window ID. Closing a local tab disconnects its client, not the remote tmux session. Killing a remote session is a separate confirmed operation.
- File shortcut uses the conversation directory. Directories outside the workspace file root use the existing LINUX area, which remains read-only in the editor.
- SSH editor supports strict UTF-8 and BOM-marked UTF-16, preserves BOM and CRLF, rejects binary/oversized files, checks content revisions before saving, and rejects symbolic-link replacement.
- SSH editor saves via a same-directory temporary file and POSIX rename. Servers without `posix-rename@openssh.com` are refused rather than falling back to truncation. Ordinary mode bits are copied; this is not a full ownership/ACL metadata-preserving editor.
- Conflict checking is optimistic, **not** cross-client atomic compare-and-swap. There is still a small interval between the last check and rename.
- Before a network save, a recovery draft is stored in app-private no-backup storage. On leaving with edits, users can explicitly retain a draft; reopening offers restoration. This is not continuous autosave of every keystroke.
- Manual destructive confirmations are independent of AI automatic tool approval.

## Deliberately not copied in this first migration

- Independent SSH host management, remote agent task engine, Windows/PowerShell scheduler and sudo editing.
- Additional transfer queue UI/bookmarks, tmux pane-history capture, window renaming and creation controls. Existing file import/export and terminal scrollback remain available.
- Root/system-area write access. Bind the desired writable directory as the workspace instead.
- Local rootfs terminals retain their original startup behavior; conversation-directory startup is added for SSH terminals.

## Validation

Initialize the pinned upstream color utilities submodule before compilation:

```sh
git submodule update --init --depth 1
```

Kotlin compilation and JVM tests can run without local Google Services configuration or the web frontend build:

```sh
ANDROID_HOME=/home/gui/Android/Sdk bash ./gradlew \
  :app:compileGithubDebugKotlin :workspace:testDebugUnitTest \
  -x :app:processGithubDebugGoogleServices -x :web:buildWebUi

ANDROID_HOME=/home/gui/Android/Sdk bash ./gradlew \
  :app:testGithubDebugUnitTest \
  --tests me.rerere.rikkahub.data.preferences.ToolApprovalPolicyTest \
  --tests me.rerere.rikkahub.data.datastore.DefaultProvidersTest \
  -x :app:processGithubDebugGoogleServices -x :web:buildWebUi
```

Tests cover embedded SSH/SFTP editing, concurrent file changes, symlink refusal, literal wildcard paths, explicit terminal directory quoting, text codecs and tmux protocol validation. A real tmux test uses an isolated temporary socket and is skipped when `/usr/bin/tmux` is unavailable; it never touches the user's default tmux server.

This is compile/JVM/integration validation, not a signed APK or Android device UI validation. Before release, exercise the chat toolbar on a narrow screen, connect to a real SSH host, switch tmux windows, navigate away/back, interrupt a transfer, and verify draft/conflict behavior on the target device.
