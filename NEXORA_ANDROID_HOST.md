# NEXORA Android Host

## Status and target device

This document defines the Android-hosted game server design. It is an architecture specification, not a claim that an Android host application or Android server runtime has already been implemented.

Initial test target: Xiaomi Pad 8, 8 GB RAM, Snapdragon 8s Gen 4, Android-based Xiaomi HyperOS 3. The exact Android API level, region firmware, available storage, and device-specific background policy must be detected at runtime rather than assumed.

The first milestone is a manually started, single-server development host. It is not a 24/7 production hosting guarantee.

## Product goal

Allow an Android tablet to run a Nukkit-MOT/NEXORA game server locally while the same tablet provides a lightweight local control screen. A browser on another device may later connect to the panel over the local network or through an authenticated remote control plane.

The Android device is the host: CPU, RAM, storage, battery, thermal headroom, and network connection all belong to the tablet. No external game-hosting provider is implied.

## Platform facts and constraints

- Android is not a general-purpose Linux server OS. Background processes may be restricted or stopped by Android or manufacturer power management.
- A visible foreground service with a persistent notification is required for a user-started long-running server process. It improves process importance but does not guarantee that the OS or user can never stop it.
- Android 14+ requires foreground service types and corresponding declarations/permissions. There is no dedicated generic "Minecraft server" foreground-service type. The implementation must select a legally appropriate type and document its purpose; do not mislabel the server as media playback or data synchronization. If a special-use type is considered, document its subtype and distribution-policy implications.
- Android 15+ applies time limits to certain foreground-service types such as dataSync and mediaProcessing. Do not use those types to disguise an always-running game server.
- Android 16 changes job quotas even when a foreground service is active. Jobs/WorkManager are for deferred maintenance, not for hosting the live game process.
- Standard desktop/server Java bytecode is not automatically executable by Android's ART runtime. Nukkit-MOT requires a compatible Java runtime. The Android prototype must prove an ARM64 OpenJDK 21+ runtime path before claiming Nukkit-MOT compatibility.
- The first implementation should evaluate a supported ARM64 OpenJDK distribution suitable for Android, licensing and redistribution terms, process lifecycle integration, and native library compatibility. Do not silently assume that desktop Linux JRE binaries will run on Android.
- HyperOS may add manufacturer-specific background and autostart restrictions. Provide a guided setup checklist, but never attempt to change system settings without the owner's action.

## Runtime architecture

The Android app is split into four modules:

1. **NEXORA Android UI**
   - Shows server state, uptime, RAM, storage, thermal status, network state, recent logs, and errors.
   - Offers explicit Start, Stop, Restart, and Backup actions.
   - Displays a persistent "server is running" state and a clear stop control.
   - Keeps the UI separate from the server process so closing the screen does not intentionally stop a healthy server.

2. **NEXORA HostService**
   - A user-started foreground service with an ongoing notification.
   - Owns the server process lifecycle and a partial wake lock only while the operator has enabled "Keep server awake".
   - Releases wake locks and resources on stop, crash, or explicit shutdown.
   - Does not hide its activity or run secretly.
   - Reports process exit codes and distinguishes user stop, crash, OS process death, and device reboot.

3. **Java Runtime Provider**
   - Supplies a verified ARM64 Java runtime compatible with the chosen Nukkit-MOT build.
   - Exposes runtime version and architecture before server start.
   - Keeps runtime files separate from server data and user plugins.
   - Does not download or execute arbitrary binaries without a verified manifest and explicit user consent.
   - Must pass a small Java compatibility probe and a Nukkit-MOT startup test on the actual target device before the feature is advertised as supported.

4. **Local Server Supervisor**
   - Starts one configured server instance with fixed, validated arguments.
   - Uses app-private directories for runtime metadata and a user-selected server data directory.
   - Captures bounded stdout/stderr logs and rotates them.
   - Tracks PID/process handle, start time, exit reason, and last successful health check.
   - Enforces configured heap and storage limits.
   - Never exposes arbitrary shell execution through the panel.

## Initial resource policy for an 8 GB tablet

The 8 GB model shares physical memory with Android, system services, the NEXORA UI, and other apps. Do not allocate all RAM to the Java heap.

Initial conservative test profile (subject to measured memory pressure):

- One server instance only.
- Java maximum heap initially capped at 1.5–2 GB; allow a lower cap if the OS reports pressure.
- Keep at least several GB available for Android and native process overhead; Java heap is not the same as total process memory.
- Do not enable Android "memory extension" / swap expansion as a substitute for physical RAM.
- Limit player count and view distance during first tests.
- Use a bounded log size and rotation policy.
- Keep worlds and backups on storage with sufficient free space; warn before disk space becomes low.
- Do not publish a player-capacity estimate until repeatable load tests on the exact tablet and server build exist.

The 1.5–2 GB heap is a starting test limit, not a guaranteed optimal value or player capacity.

## Thermal and battery safety

- Show temperature/thermal status where Android exposes it, plus a simple Normal / Warm / Hot state.
- Reduce optional background work when the device becomes warm.
- On severe thermal status, warn the operator and offer a graceful stop. Do not abruptly kill the server or corrupt world data as an automatic response.
- Warn against direct sunlight, enclosed cases, soft surfaces that block heat dissipation, and sustained charging while the device is hot.
- Provide an operator-configurable charging policy. Never bypass Xiaomi or Android battery safety mechanisms.
- Do not promise that continuous charging is harmless. Battery aging is affected by temperature and charge conditions.
- Offer graceful shutdown before the user powers off or restarts the tablet.
- Backups must be explicit, verified, and stored separately where possible. Never overwrite an existing backup automatically.

## Network model

- The server listens only on the configured game port and intended network interfaces.
- Local-network access is the first milestone. Show the current LAN address and port, but explain that it can change.
- Internet access requires router port forwarding or a suitable tunnel/relay. Carrier-grade NAT, mobile network policies, and ISP filtering may prevent inbound connections.
- Never expose the Android debugging port, local control API, SSH, or unrestricted filesystem interface to the public internet.
- Remote panel access must use the NEXORA control-plane design: authenticated operator identity, server-scoped authorization, encrypted transport, revocable host identity, and audited typed operations.
- The local UI must not bind an unauthenticated management API to 0.0.0.0. If LAN panel access is added, require pairing and authentication.

## Process lifecycle and recovery

- The operator explicitly starts the server from the app.
- The service posts an ongoing notification with server name, state, and a stop action.
- Screen-off and UI navigation do not intentionally stop the server.
- A crash is recorded with a bounded diagnostic summary; automatic restart is opt-in, rate-limited, and disabled by default for the first prototype.
- Repeated crashes trigger a cooldown and a visible warning, not an endless restart loop.
- After Android kills the process or the device reboots, the app must not silently start a game server without the operator's prior explicit opt-in. The first prototype requires manual restart.
- On device shutdown, Android force-stop, battery exhaustion, or severe OS intervention, the server may stop. The app must state this limitation clearly.
- If the tablet loses internet, the local server may continue for LAN users; loss of control-plane connectivity must not stop it.

## Storage and data integrity

- Keep executable runtime files, server data, logs, and backups in separate directories.
- Validate all paths against app-configured roots; reject traversal and unsafe symbolic-link cases where applicable.
- Use atomic writes for NEXORA configuration and job metadata.
- Do not write high-volume logs indefinitely. Rotate by size and age.
- Never run cleanup against a broad user-selected folder. Delete only files created by a specific NEXORA operation after validating their exact paths.
- Backup creation must be explicit and non-overwriting. A backup is not considered verified until its manifest and file hashes have been checked.
- Restoring worlds or server data must be a separate, confirmation-gated workflow and must never happen automatically after a crash.

## Privacy and security

- The app is transparent: persistent notification, visible server state, and a clear stop action.
- No hidden remote access, covert persistence, or bypass of Android security controls.
- Keep host credentials in Android Keystore-backed storage where compatible.
- Do not log player chat by default.
- Never include passwords, enrollment codes, private keys, or access tokens in logs or support bundles.
- Remote commands are typed and allowlisted. No arbitrary shell or remote terminal in the first version.
- The Android app and server process run under the app sandbox. This does not make third-party Java plugins safe; plugins executing in the same JVM remain trusted code.

## Implementation stages

1. **Device capability probe:** detect Android API level, ABI, available RAM, free storage, thermal API availability, and battery state on Xiaomi Pad 8.
2. **Java runtime proof:** validate an ARM64 OpenJDK 21+ runtime and run a minimal Java process; record the license and packaging decision.
3. **Foreground service prototype:** start a harmless test process with a persistent notification, screen-off behavior, explicit stop, and correct cleanup.
4. **Nukkit-MOT proof:** start one disposable Nukkit-MOT server, join from a LAN client, save/quit cleanly, and verify world persistence.
5. **Resource controls:** implement heap cap, log rotation, disk-space warnings, thermal warnings, and graceful stop.
6. **Local control UI:** add server status and explicit lifecycle actions.
7. **LAN access:** add authenticated, paired read-only status first; then approved typed actions.
8. **Remote control plane:** enroll the tablet as a host using the versioned NEXORA protocol, with revocation and audited authorization.
9. **Reliability testing:** screen off, app UI closed, Wi-Fi reconnect, low battery, thermal throttling, process crash, storage full, reboot, and Android force-stop.

## Acceptance criteria for first public test

- The app clearly states it is an experimental single-server host.
- Java runtime architecture/version is verified before launch.
- The server starts and accepts one LAN client on the target tablet.
- Screen-off operation is measured for a defined test period; no claim of indefinite uptime is made.
- The ongoing notification remains visible while the service runs.
- Stop performs a graceful server shutdown and world save.
- A forced process termination is detected on next launch and reported accurately.
- Memory, thermal, battery, and storage warnings are visible.
- The server does not restart after reboot unless the operator explicitly enabled and confirmed that behavior.
- No external/public management endpoint is exposed by default.

## Operator guidance

For an early test, keep the tablet on a hard, ventilated surface, remove a heat-trapping case if temperatures rise, use a stable Wi-Fi connection, avoid running demanding foreground games at the same time, and monitor temperature and battery health. A tablet is suitable for experimentation and small private sessions; a dedicated PC/VPS remains the more reliable choice for a public 24/7 server.
