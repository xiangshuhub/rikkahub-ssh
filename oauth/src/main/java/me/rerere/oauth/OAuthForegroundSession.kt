package me.rerere.oauth

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/** Keep device polling / manual-code login alive while the browser is in front. */
suspend fun <T> withOAuthForegroundSession(context: Context, block: suspend () -> T): T {
    val sessionId = "authorization-${UUID.randomUUID()}"
    try {
        check(OAuthCallbackForegroundService.acquire(context, sessionId)) {
            "Unable to keep authorization active in the background"
        }
        return block()
    } finally {
        withContext(NonCancellable) {
            OAuthCallbackForegroundService.release(context, sessionId)
        }
    }
}
