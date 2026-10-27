package me.rerere.rikkahub.utils

import org.junit.Assert.*
import org.junit.Test

class ForkReleaseTest {
    private val valid = ForkReleaseAsset("app-github-arm64-v8a-release.apk",
        "https://github.com/xiangshuhub/rikkahub-ssh/releases/download/v3.1.2/app-github-arm64-v8a-release.apk", 1048576)

    @Test fun acceptsOnlyThisForkReleaseApks() {
        val release = ForkRelease("v3.1.2", "2026-01-01T00:00:00Z", "Changes", assets = listOf(
            valid, valid.copy(name = "app-debug.apk"), valid.copy(url = "https://example.com/app-release.apk"),
        ))
        val update = release.toUpdateInfo("3.1.1")
        assertEquals("3.1.2", update.version)
        assertEquals(1, update.downloads.size)
        assertEquals(valid.url, update.downloads.single().url)
    }

    @Test fun unpublishedNightlyAndSourceOnlyReleasesDoNotOfferUpdates() {
        val release = ForkRelease("v3.1.2", "2026-01-01T00:00:00Z", assets = listOf(valid))
        listOf(release.copy(tag = "nightly"), release.copy(draft = true), release.copy(prerelease = true),
            release.copy(assets = emptyList())).forEach {
            assertEquals(noPublishedUpdate("3.1.1"), it.toUpdateInfo("3.1.1"))
        }
    }
}
