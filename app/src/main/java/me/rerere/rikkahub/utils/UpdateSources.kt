package me.rerere.rikkahub.utils

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

internal enum class UpdateSource(val apiUrl: String, val downloadPrefix: String) {
    GITEE("https://gitee.com/api/v5/repos/chunjie008/rikkahub-ssh/releases/latest",
        "https://gitee.com/chunjie008/rikkahub-ssh/releases/download/"),
    GITHUB("https://api.github.com/repos/xiangshuhub/rikkahub-ssh/releases/latest",
        "https://github.com/xiangshuhub/rikkahub-ssh/releases/download/"),
}

/** Null means HTTP 404 (no release). A valid current/older release is not a network failure. */
internal suspend fun fetchPreferredUpdate(currentVersion: String, fetch: suspend (UpdateSource) -> String?): UpdateInfo {
    val json = Json { ignoreUnknownKeys = true }
    val failures = mutableListOf<Exception>()
    for (source in UpdateSource.entries) {
        try {
            val body = fetch(source) ?: continue
            val result = json.decodeFromString<ForkRelease>(body).toUpdateInfo(currentVersion, source)
            check(result.downloads.isNotEmpty()) { "${source.name}: no valid stable APK release" }
            return result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += e
        }
    }
    if (failures.isEmpty()) return noPublishedUpdate(currentVersion)
    throw IllegalStateException("Gitee and GitHub update sources are unavailable", failures.last()).also { error ->
        failures.dropLast(1).forEach(error::addSuppressed)
    }
}
