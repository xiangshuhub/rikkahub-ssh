package me.rerere.rikkahub.data.ai.account

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Accept a redirect URL, code#state, query string, or a bare authorization code. */
internal fun parseAuthorizationInput(input: String, expectedState: String): String {
    val value = input.trim()
    require(value.isNotEmpty()) { "Missing authorization code" }
    val url = value.toHttpUrlOrNull()
        ?: if (value.startsWith("code=")) "https://localhost/?$value".toHttpUrlOrNull() else null
    val state = url?.queryParameter("state") ?: value.takeIf { url == null && '#' in it }?.substringAfter('#')
    require(state == null || state == expectedState) { "OAuth state mismatch" }
    require(url?.queryParameter("error") == null) { "Authorization denied" }
    return (if (url != null) url.queryParameter("code") else value.substringBefore('#'))
        ?.takeIf { it.isNotBlank() } ?: error("Missing authorization code")
}
