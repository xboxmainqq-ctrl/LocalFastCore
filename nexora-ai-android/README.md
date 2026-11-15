# NEXORA AI Android Host

Prototype target: Android 8.0+ (API 26), compile/target SDK 35, Java 17.

## Current scope

- User-started foreground service.
- Local HTTP API bound only to 127.0.0.1:8765.
- Random 256-bit bearer token stored in private app preferences.
- GET /health and GET /v1/capabilities.
- API requests require an Authorization: Bearer <token> header.
- No cloud model, remote-network, project-editing, or build-worker integration is included yet.

## Build

Open this directory in Android Studio, or use Gradle 8.9 with JDK 17:

    gradle assembleDebug

APK output:

    app/build/outputs/apk/debug/app-debug.apk

## Test on the tablet

1. Install the debug APK.
2. Open NEXORA AI Host and tap Start local server.
3. Use an on-device terminal to call:

    curl -H "Authorization: Bearer YOUR_TOKEN" http://127.0.0.1:8765/health

The app displays the token. Keep it private.

## Security boundary

The server deliberately binds to loopback only. It is not reachable over Wi-Fi or mobile data. Do not expose this prototype to the public internet. Remote pairing, TLS, user authentication, and a private-network tunnel must be designed and reviewed before remote access is enabled.

Android may restrict foreground services and background execution. The service is user-started and displays a persistent notification. Play Store distribution requires a separate policy review, especially for the special-use foreground service type.
