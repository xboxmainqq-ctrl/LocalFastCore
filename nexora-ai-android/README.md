# NEXORA AI Android Host

Prototype target: Android 8.0+ (API 26), compile/target SDK 35, Java 17.

## Current scope

- User-started foreground service.
- Local HTTP API bound only to 127.0.0.1:8765.
- Random 256-bit bearer token stored in private app preferences.
- GET /health and GET /v1/capabilities.
- Embedded WebView dashboard and six early-stage creation tools.
- Free-only OpenRouter text chat bridge using `openrouter/free`; no paid fallback.
- GitHub repository connection using a fine-grained personal access token.
- GitHub repository and branch listing, branch creation, and atomic multi-file commits.
- GitHub token encrypted at rest with Android Keystore (AES-GCM).
- GitHub writes require a user confirmation in the dashboard.
- GitHub commit API currently supports up to 50 text files and 1 MiB total per commit.
- Project workspace is still browser-local; a full project file manager and direct AI-to-workspace editing are not yet implemented.
- No remote-network access to the local API, no public website deployment, and no full autonomous build agent yet.

## GitHub setup

1. In GitHub, create a fine-grained personal access token restricted to the exact repository you want NEXORA to access.
2. Grant repository permissions: **Metadata: Read-only** and **Contents: Read and write**.
3. Open the Android Host → Dashboard → GitHub.
4. Paste the token and connect. The token is encrypted with Android Keystore and is not returned to the web UI.
5. Load repositories, choose a repository and branch, then enter a file path, commit message, and file content.
6. Review the confirmation dialog before the commit is sent.

Prefer a dedicated working branch such as `nexora-ai/my-feature`. Avoid committing directly to `main`. Never paste the token into the AI chat, project files, or a public repository. You can revoke the token in GitHub settings at any time; use the Disconnect button to erase its local copy.

The current UI saves a text file per commit. The next planned step is connecting the GitHub workspace to the project file manager so AI-generated changes can be reviewed as a diff and saved together.

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

GitHub access is limited by the permissions and repository selection of the fine-grained token. GitHub Apps with short-lived tokens and installation-level repository restrictions are a planned upgrade over manually supplied tokens.

Android may restrict foreground services and background execution. The service is user-started and displays a persistent notification. Play Store distribution requires a separate policy review, especially for the special-use foreground service type.
