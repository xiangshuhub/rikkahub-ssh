package me.rerere.rikkahub.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateSourcesTest {
    private fun release(source: UpdateSource, version: String = "3.1.3") = """
        {"tag_name":"v$version","created_at":"2026-01-01T00:00:00Z","assets":[
        {"name":"app-github-arm64-v8a-release.apk","browser_download_url":"${source.downloadPrefix}v$version/app-github-arm64-v8a-release.apk"}]}
    """.trimIndent()

    @Test fun giteeIsPreferredAndMissingAssetSizeIsNotZeroMegabytes() = runBlocking {
        val calls = mutableListOf<UpdateSource>()
        val result = fetchPreferredUpdate("3.1.2") { calls += it; release(it) }
        assertEquals(listOf(UpdateSource.GITEE), calls)
        assertTrue(result.downloads.single().url.startsWith("https://gitee.com/"))
        assertEquals("", result.downloads.single().size)
        assertEquals("2026-01-01T00:00:00Z", result.publishedAt)
    }

    @Test fun networkAndMalformedAndEmptyReleaseFallbackToGithub() = runBlocking {
        for (mode in listOf("network", "html", "empty", "missing", "wrong_host")) {
            val calls = mutableListOf<UpdateSource>()
            val result = fetchPreferredUpdate("3.1.2") { source ->
                calls += source
                if (source == UpdateSource.GITHUB) release(source) else when (mode) {
                    "network" -> throw java.io.IOException("timeout")
                    "html" -> "<html>Login</html>"
                    "empty" -> """{"tag_name":"v3.1.3","assets":[]}"""
                    "missing" -> null
                    else -> release(UpdateSource.GITHUB)
                }
            }
            assertEquals(listOf(UpdateSource.GITEE, UpdateSource.GITHUB), calls)
            assertTrue(result.downloads.single().url.startsWith("https://github.com/"))
        }
    }

    @Test fun validCurrentReleaseDoesNotContactGithub() = runBlocking {
        val result = fetchPreferredUpdate("3.1.3") {
            assertEquals(UpdateSource.GITEE, it)
            release(it)
        }
        assertEquals("3.1.3", result.version)
    }

    @Test fun cancellationDoesNotTriggerFallback() = runBlocking {
        var calls = 0
        val result = runCatching { fetchPreferredUpdate("3.1.2") { calls++; throw CancellationException() } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, calls)
    }

    @Test fun bothMissingIsNoUpdateButBothFailedIsError() = runBlocking {
        assertEquals(noPublishedUpdate("3.1.2"), fetchPreferredUpdate("3.1.2") { null })
        assertTrue(runCatching { fetchPreferredUpdate("3.1.2") { throw java.io.IOException("offline") } }.isFailure)
    }
}
