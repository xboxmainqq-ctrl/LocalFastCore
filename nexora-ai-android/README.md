# NEXORA AI Android Host

Prototype target: Android 8.0+ (API 26), compile/target SDK 35, Java 17.

## Current scope

- User-started foreground service.
- Local HTTP API bound only to 127.0.0.1:8765.
- Random 256-bit bearer token stored in private app preferences.
- GET /health and GET /v1/capabilities.
- Embedded WebView dashboard and six early-stage creation tools.
- Free-only OpenRouter text chat bridge using `openrouter/free`; no paid fallback.
- Persistent engineering system instructions loaded from `app/src/main/assets/nexora-system-prompt.md` for every chat request.
- Curated engineering knowledge base and staged capability roadmap in `docs/nexora-ai/knowledge-base/` and `docs/nexora-ai/agent-capability-roadmap.md`.
- GitHub App Device Flow sign-in with encrypted access/refresh token storage and automatic token refresh.
- Fine-grained personal access token remains available as a manual fallback.
- GitHub repository and branch listing, branch creation, repository folder browsing, UTF-8 file reading, and atomic multi-file commits.
- Workspace editor with staged file changes and a confirmation step before writing commits.
- AI-assisted editing of the currently opened file: the file content is sent as task context to the free-only model; the proposed complete file is reviewed and staged by the user before commit.
- GitHub token encrypted at rest with Android Keystore (AES-GCM).
- GitHub writes require a user confirmation in the dashboard.
- GitHub commit API currently supports up to 50 text files and 1 MiB total per commit.
- The GitHub workspace can browse folders, read text files, and stage AI-assisted edits for review. It is not yet a full local Git clone or an autonomous terminal/build agent.
- No remote-network access to the local API, no public website deployment, and no full autonomous build agent yet.

## GitHub setup

Preferred sign-in is GitHub App Device Flow:
1. Register a GitHub App in GitHub Developer Settings. Enable **Device Flow**.
2. Give the app repository permissions **Metadata: Read-only** and **Contents: Read and write**. Install the app only on repositories you want NEXORA to access.
3. Open Android Host → Dashboard → GitHub and enter the app's **Client ID** (not App ID and never a client secret).
4. Tap **Войти через GitHub**, open the displayed verification URL, enter the one-time code, and approve access.
5. NEXORA stores access/refresh tokens encrypted with Android Keystore and refreshes expiring access tokens automatically.
6. Load repositories, choose a repository and branch, browse files, ask AI to rewrite the opened file, review/stage changes, then confirm a multi-file commit.

A fine-grained personal access token can still be used as a manual fallback. Restrict it to the exact repository and grant Metadata: Read-only plus Contents: Read and write. Never paste credentials into the AI chat or source files.

Prefer a dedicated working branch such as `nexora-ai/my-feature`. Avoid committing directly to `main`. Never paste the token into the AI chat, project files, or a public repository. You can revoke the token in GitHub settings at any time; use the Disconnect button to erase its local copy.

The workspace editor can read files from a selected repository branch, request an AI rewrite of the opened file using the current file as context, stage the returned content, and commit up to 50 staged text files together (maximum 1 MiB total). Review every generated change before committing. The AI response is not proof that code compiles; no build is run on the tablet by this editor.

## AI provider

The chat uses OpenRouter's `openrouter/free` route. Free model availability and quotas can change. If no free route is available, NEXORA stops instead of switching to a paid model. Do not add provider credits if you require a strict no-spend setup.

## Build

Open this directory in Android Studio, or use Gradle 8.9 with JDK 17:

    gradle assembleDebug

The GitHub Actions workflow bundles `nexora-ai-web/` into the Android app before building.

APK output:

    app/build/outputs/apk/debug/app-debug.apk

## Test on the tablet

1. Install the debug APK.
2. Open NEXORA AI Host and tap Start local server.
3. Use an on-device terminal to call:

    curl -H "Authorization: Bearer YOUR_TOKEN" http://127.0.0.1:8765/health

The app displays the local API token. Keep it private.

## Security boundary

The local API deliberately binds to loopback only. It is not reachable over Wi-Fi or mobile data. Do not expose this prototype to the public internet. Remote pairing, TLS, user authentication, and a private-network tunnel must be designed and reviewed before remote access is enabled.

GitHub access is limited by the permissions and repository selection of the fine-grained token. GitHub App Device Flow is the preferred login path. The app's installation selection and repository permissions constrain access; device authorization codes are one-time and expire.

Android may restrict foreground services and background execution. The service is user-started and displays a persistent notification. Play Store distribution requires a separate policy review, especially for the special-use foreground service type.
