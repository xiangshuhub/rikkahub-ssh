package me.rerere.rikkahub.utils

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal fun noPublishedUpdate(currentVersion: String) = UpdateInfo(
    version = currentVersion, publishedAt = "1970-01-01T00:00:00Z", changelog = "", downloads = emptyList(),
)

@Serializable
internal data class ForkRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("published_at") val publishedAt: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ForkReleaseAsset> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
) {
    fun toUpdateInfo(currentVersion: String, source: UpdateSource = UpdateSource.GITHUB): UpdateInfo {
        val version = tag.removePrefix("v")
        if (draft || prerelease || !Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version)) return noPublishedUpdate(currentVersion)
        val downloads = assets.filter {
            Regex("[A-Za-z0-9._-]+-release\\.apk").matches(it.name) && !it.name.contains("debug", ignoreCase = true) &&
                it.url == "${source.downloadPrefix}$tag/${it.name}"
        }.map { UpdateDownload(it.name, it.url, if (it.size > 0) "${it.size / 1024 / 1024} MB" else "") }
        if (downloads.isEmpty()) return noPublishedUpdate(currentVersion)
        val timestamp = publishedAt ?: createdAt ?: error("Release timestamp is missing")
        java.time.Instant.parse(timestamp)
        return UpdateInfo(version, timestamp, body.orEmpty(), downloads)
    }
}

@Serializable
internal data class ForkReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)
