package me.rerere.rikkahub.utils

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal fun noPublishedUpdate(currentVersion: String) = UpdateInfo(
    version = currentVersion, publishedAt = "1970-01-01T00:00:00Z", changelog = "", downloads = emptyList(),
)

@Serializable
internal data class ForkRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("published_at") val publishedAt: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<ForkReleaseAsset> = emptyList(),
) {
    fun toUpdateInfo(currentVersion: String): UpdateInfo {
        val version = tag.removePrefix("v")
        if (draft || prerelease || !Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(version)) return noPublishedUpdate(currentVersion)
        val downloads = assets.filter {
            it.name.endsWith("-release.apk") && !it.name.contains("debug", ignoreCase = true) &&
                it.url.startsWith("https://github.com/xiangshuhub/rikkahub-ssh/releases/download/")
        }.map { UpdateDownload(it.name, it.url, "${it.size / 1024 / 1024} MB") }
        if (downloads.isEmpty()) return noPublishedUpdate(currentVersion)
        java.time.Instant.parse(publishedAt)
        return UpdateInfo(version, publishedAt, body.orEmpty(), downloads)
    }
}

@Serializable
internal data class ForkReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
)
