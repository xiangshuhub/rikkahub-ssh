package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.workspace.WorkspaceStorageArea

@Serializable
data class WorkspaceBrowserLocation(val area: String = "FILES", val path: String = "") {
    fun storageArea() = WorkspaceStorageArea.valueOf(area)
    fun isValid() = area in WorkspaceStorageArea.entries.map { it.name } &&
        !path.startsWith('/') && '\u0000' !in path &&
        (path.isEmpty() || path.split('/').none { it.isEmpty() || it == "." || it == ".." })
}

@Serializable
data class WorkspaceBookmark(val location: WorkspaceBrowserLocation, val name: String)

internal fun updateWorkspaceBookmarks(
    bookmarks: List<WorkspaceBookmark>, location: WorkspaceBrowserLocation, name: String,
): List<WorkspaceBookmark> {
    require(location.isValid())
    require(name.trim().isNotEmpty() && name.trim().length <= 100)
    val entry = WorkspaceBookmark(location, name.trim())
    return if (bookmarks.any { it.location == location }) {
        bookmarks.map { if (it.location == location) entry else it }
    } else bookmarks + entry
}

/** Browser-only metadata. Never changes assistant/workspace execution settings or filesystem permissions. */
class WorkspaceBrowserPreferences internal constructor(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    constructor(context: Context) : this(context.getSharedPreferences("workspace_browser", Context.MODE_PRIVATE))
    private constructor(prefs: android.content.SharedPreferences) : this(
        { prefs.getString(it, null) },
        { key, value -> prefs.edit().putString(key, value).apply() },
    )
    private val json = Json { ignoreUnknownKeys = true }

    fun lastLocation(id: String): WorkspaceBrowserLocation? = runCatching {
        json.decodeFromString<WorkspaceBrowserLocation>(read("last:$id") ?: return null)
            .takeIf { it.isValid() }
    }.getOrNull()

    fun saveLocation(id: String, location: WorkspaceBrowserLocation) {
        require(location.isValid())
        write("last:$id", json.encodeToString(location))
    }

    fun bookmarks(id: String): List<WorkspaceBookmark> = runCatching {
        json.decodeFromString<List<WorkspaceBookmark>>(read("bookmarks:$id") ?: return emptyList())
            .filter { it.location.isValid() && it.name.isNotBlank() }
            .distinctBy { it.location }
    }.getOrDefault(emptyList())

    fun saveBookmark(id: String, location: WorkspaceBrowserLocation, name: String): List<WorkspaceBookmark> = synchronized(lock) {
        updateWorkspaceBookmarks(bookmarks(id), location, name).also { saveBookmarks(id, it) }
    }

    fun removeBookmark(id: String, location: WorkspaceBrowserLocation): List<WorkspaceBookmark> = synchronized(lock) {
        bookmarks(id).filterNot { it.location == location }.also { saveBookmarks(id, it) }
    }

    private fun saveBookmarks(id: String, bookmarks: List<WorkspaceBookmark>) {
        write("bookmarks:$id", json.encodeToString(bookmarks))
    }

    private companion object { val lock = Any() }
}
