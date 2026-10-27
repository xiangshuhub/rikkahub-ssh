package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import me.rerere.workspace.ssh.textRevision

internal data class WorkspaceEditorDraft(val revision: String, val text: String)

/** Private app storage, keyed by workspace and path; never included in a model request. */
internal class WorkspaceEditorDraftStore(context: Context, id: String, path: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "workspace-editor-drafts/" +
        textRevision("$id\u0000$path".toByteArray()) + ".json"))

    suspend fun read(): WorkspaceEditorDraft? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext null
        val json = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        WorkspaceEditorDraft(json.getString("revision"), json.getString("text"))
    }

    suspend fun save(draft: WorkspaceEditorDraft) = withContext(Dispatchers.IO) {
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().put("revision", draft.revision).put("text", draft.text).toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { file.delete() }
}
