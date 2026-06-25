# HuggingFace OAuth setup (gated model downloads)

The AI Math Solver downloads gated Gemma models (Gemma 3n E2B / E4B) using a
**"Sign in with Hugging Face"** flow — OAuth 2.0 authorization-code + PKCE via
[AppAuth](https://github.com/openid/AppAuth-Android). To enable it you register a
HuggingFace OAuth app once and drop its Client ID into the app.

## 1. Register the OAuth app

Go to <https://huggingface.co/settings/applications/new> and create an app:

| Field | Value |
|-------|-------|
| Application name | CalculatorM3 (anything) |
| Redirect URI | `calculatorm3://hf-auth` |
| Scopes | `openid`, `profile`, `gated-repos` |
| Client secret | **None** — create it as a *public* app |

`gated-repos` is the scope that grants read access to gated repos the user has been
granted access to (the Gemma case). The redirect URI's scheme (`calculatorm3`) must match
`manifestPlaceholders["appAuthRedirectScheme"]` in `app/build.gradle.kts`.

## 2. Configure the Client ID

Copy the generated **Client ID** into
`app/src/main/java/com/vagujhelyigergely/calculatorm3/auth/HuggingFaceAuth.kt`:

```kotlin
const val CLIENT_ID = "your-client-id-here"
```

If you change the redirect URI, update both `REDIRECT_URI` there and the Gradle
`appAuthRedirectScheme` placeholder so the scheme still matches.

## 3. End-user flow

1. User picks a gated model and taps download → **Sign in with Hugging Face**.
2. A Custom Tab opens; the user logs in and authorizes the app.
3. The app exchanges the authorization code for an access token and downloads with it
   (`Authorization: Bearer <token>`).
4. If the user hasn't accepted that model's license yet, the download returns **403** and
   the app opens the model page so they can click *"Agree and access repository"*, then retry.

Access tokens are short-lived (~8 h); when one expires, a later download shows
**Sign in again**, which re-runs the flow.
