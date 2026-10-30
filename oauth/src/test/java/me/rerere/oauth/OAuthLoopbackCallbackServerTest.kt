package me.rerere.oauth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class OAuthLoopbackCallbackServerTest {
    private val httpClient = OkHttpClient()

    @Test
    fun `callback is delivered only to matching state`() = runBlocking {
        val server = OAuthLoopbackCallbackServer()
        val session = server.openSession("expected-state")
        try {
            assertTrue(session.redirectUri.startsWith("http://localhost:"))
            execute("${session.redirectUri}?code=wrong-code&state=wrong-state").use { response ->
                assertEquals(400, response.code)
            }

            val callbackAndClose = async(Dispatchers.IO) {
                session.awaitCallback(2.seconds).also { session.close() }
            }
            withContext(Dispatchers.IO) {
                execute("${session.redirectUri}?code=auth-code&state=expected-state").use { response ->
                    assertEquals(200, response.code)
                    assertTrue(response.body.string().contains("Authorization complete"))
                }
            }

            val callback = callbackAndClose.await()
            assertEquals("auth-code", callback?.code)
            assertEquals("expected-state", callback?.state)
            assertFalse(callback?.error != null)
        } finally {
            session.close()
        }
    }

    @Test
    fun `OpenRouter callback authenticates a random path without a state query`() = runBlocking {
        val server = OAuthLoopbackCallbackServer(stateInPath = true)
        val session = server.openSession("random-session-secret")
        try {
            assertTrue(session.redirectUri.endsWith("/random-session-secret"))
            execute(session.redirectUri.replace("random-session-secret", "wrong") + "?code=wrong").use {
                assertEquals(400, it.code)
            }
            execute("${session.redirectUri}?code=openrouter-code").use { assertEquals(200, it.code) }
            val result = session.awaitCallback(2.seconds)
            assertEquals("openrouter-code", result?.code)
            assertEquals("random-session-secret", result?.state)
            execute("${session.redirectUri}?code=replayed").use { assertEquals(409, it.code) }
        } finally {
            session.close()
        }
    }

    private fun execute(url: String) = httpClient.newCall(
        Request.Builder()
            .url(url)
            .build()
    ).execute()
}
