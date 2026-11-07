package app.mymemory.ledger.ingest

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Read-only Gmail access. Google Identity returns short-lived (1 hour) access tokens and manages
 * refresh itself, so the app never stores a password or a refresh token.
 */
object GmailAuth {
    private val SCOPE = Scope("https://www.googleapis.com/auth/gmail.readonly")

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    /** Token without any UI. Null if consent was never given or has to be given again. */
    suspend fun silentToken(context: Context): String? = try {
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        if (result.hasResolution()) null else result.accessToken
    } catch (e: Exception) {
        null
    }
}
