package com.vagujhelyigergely.calculatorm3.auth

import android.net.Uri
import net.openid.appauth.AuthorizationServiceConfiguration

/**
 * Configuration for the "Sign in with Hugging Face" OAuth flow used to download gated
 * Gemma models (see [com.vagujhelyigergely.calculatorm3.ai.AiModel.requiresAuth]).
 *
 * Authorization-code + PKCE against a **public** OAuth app (no client secret).
 *
 * ## One-time setup
 * Register an OAuth app at https://huggingface.co/settings/applications/new:
 *  - **Redirect URI**: `calculatorm3://hf-auth` — must equal [REDIRECT_URI], and its scheme
 *    must match `manifestPlaceholders["appAuthRedirectScheme"]` in `app/build.gradle.kts`.
 *  - **Scopes**: `openid`, `profile`, `gated-repos`.
 *  - Create it **without a client secret** (public app).
 *  - Paste the generated Client ID into [CLIENT_ID].
 */
object HuggingFaceAuth {

    /**
     * OAuth client ID for the HuggingFace OAuth app (public app, no secret) registered at
     * https://huggingface.co/settings/applications/new. A public client ID is not a secret,
     * so it's safe to embed in the distributed app.
     */
    const val CLIENT_ID = "5e432a93-ef25-4b1b-8914-a03ec8651cab"

    /** Custom-scheme redirect; scheme must match the Gradle `appAuthRedirectScheme` placeholder. */
    val REDIRECT_URI: Uri = Uri.parse("calculatorm3://hf-auth")

    /**
     * `gated-repos` grants read access to public gated repos the user has been granted access
     * to (the Gemma case); `openid`/`profile` identify the user during consent.
     */
    val SCOPES = listOf("openid", "profile", "gated-repos")

    val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://huggingface.co/oauth/authorize"),
        Uri.parse("https://huggingface.co/oauth/token")
    )
}
