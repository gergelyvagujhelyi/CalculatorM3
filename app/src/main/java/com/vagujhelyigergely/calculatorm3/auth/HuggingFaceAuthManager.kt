package com.vagujhelyigergely.calculatorm3.auth

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.ResponseTypeValues
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Thin wrapper around AppAuth for the HuggingFace sign-in flow. Owns a single
 * [AuthorizationService] — call [dispose] when done (the owning ViewModel does this in
 * `onCleared`).
 */
class HuggingFaceAuthManager(context: Context) {

    private val authService = AuthorizationService(context.applicationContext)

    /**
     * Build the intent that opens the Custom Tab for authorization. Launch it with an
     * `ActivityResultLauncher` (StartActivityForResult) and feed the returned Intent to
     * [exchangeCodeForToken]. PKCE is added automatically by AppAuth.
     */
    fun authRequestIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            HuggingFaceAuth.serviceConfig,
            HuggingFaceAuth.CLIENT_ID,
            ResponseTypeValues.CODE,
            HuggingFaceAuth.REDIRECT_URI
        ).setScopes(HuggingFaceAuth.SCOPES).build()
        return authService.getAuthorizationRequestIntent(request)
    }

    /**
     * Exchange the authorization-code result for an access token.
     *
     * @param data the Intent delivered to the ActivityResultLauncher (must be non-null and
     *   carry a successful authorization response — callers handle cancellation first).
     * @return the HuggingFace access token, usable directly as `Authorization: Bearer <token>`.
     * @throws Exception ([AuthorizationException] or [IllegalStateException]) on failure.
     */
    suspend fun exchangeCodeForToken(data: Intent): String =
        suspendCancellableCoroutine { cont ->
            val response = AuthorizationResponse.fromIntent(data)
            val error = AuthorizationException.fromIntent(data)
            if (response == null) {
                cont.resumeWithException(error ?: IllegalStateException("Authorization failed"))
                return@suspendCancellableCoroutine
            }
            // Public client (no secret): the token request carries the PKCE verifier, so no
            // client authentication is required.
            authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, tokenError ->
                val token = tokenResponse?.accessToken
                if (token != null) {
                    cont.resume(token)
                } else {
                    cont.resumeWithException(tokenError ?: IllegalStateException("Token exchange failed"))
                }
            }
        }

    fun dispose() {
        authService.dispose()
    }
}
