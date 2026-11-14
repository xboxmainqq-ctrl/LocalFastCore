# NEXORA Development Test Server

## Purpose

Use one disposable test server on the developer's Xiaomi Pad 8 (8 GB RAM) to validate NEXORA changes before preparing a release candidate for deployment to the user's MKPHost hosting account.

The tablet is a development lab, not the production host. The production server remains on MKPHost after the user explicitly installs an approved build.

## Environments

### Development — Xiaomi Pad 8

- One test instance at a time.
- ARM64 Android runtime and Nukkit-MOT compatibility must be proven before use.
- Start with a conservative Java heap cap of 1.5–2 GB, then adjust only from measured memory pressure.
- Use a separate disposable world and test data directory.
- Use test-only operator credentials and test player accounts.
- Keep a clean baseline copy of the test server configuration and world.
- Do not use the user's production world, production player data, or production secrets.
- LAN connectivity is the first target. Public internet exposure is not required for initial tests.

### Production — MKPHost

- Production installation is a separate, explicit operator action.
- Do not assume MKPHost supports arbitrary server forks, custom Java versions, custom startup commands, or uploading a full server runtime. Confirm the host's supported server software and installation method before preparing the final package.
- Never copy development secrets, test worlds, debug logs, or test credentials into production.
- Preserve required legacy plugin IDs, permissions, commands, package names, and data paths during the compatibility phase.
- Take a verified backup before replacing any production server files.
- Keep the previous known-good server package available for manual rollback.
- Do not deploy automatically from GitHub CI to MKPHost.

## Build channels

1. **Development snapshot** — frequent internal builds; may contain unfinished features and is not for production.
2. **Test candidate** — a versioned build that passes automated checks and is installed only on the disposable tablet test server.
3. **Release candidate** — a test candidate that passes the compatibility checklist and is packaged with checksums and provenance.
4. **Production release** — explicitly selected by the operator for MKPHost installation after backup and host compatibility confirmation.

Never label a build "stable" solely because it compiles.

## Test cycle for each change

1. Record the change and its expected behavior.
2. Build from a known Git commit.
3. Run automated unit and integration tests available in CI.
4. Generate a SHA-256 checksum for the exact JAR/package.
5. Install the candidate into the tablet's disposable test directory.
6. Start the server and inspect startup logs for warnings/errors.
7. Join with a Bedrock client over the local network where the runtime supports it.
8. Exercise the changed feature and the related regression checks.
9. Stop gracefully and confirm world/player data persistence.
10. Record device RAM, thermal state, uptime, and any Android process restrictions.
11. Keep or reject the candidate; never overwrite the last known-good test candidate without preserving it.

## Minimum compatibility checklist

- Nukkit-MOT starts and completes plugin loading.
- Existing LocalFastCore plugin identity and data paths remain compatible where required.
- Existing ClassAbilities and other required legacy plugins load without linkage errors.
- Player join/quit, chat, commands, permissions, forms/UI, and world save/load work.
- No repeating fatal exceptions, unbounded memory growth, or runaway log growth.
- Graceful stop completes and world data is readable after restart.
- Network connection works from a Bedrock client on the same LAN.
- Security audit and integrity checks remain report-only unless a separately reviewed enforcement feature is explicitly enabled.
- Backup creation and verification are tested on disposable data before any restore workflow is considered.

## Android-specific limitations

- Android may stop the process because of memory pressure, thermal management, battery policy, force-stop, or reboot.
- A foreground service and persistent notification improve long-running process behavior but do not guarantee indefinite uptime.
- The first prototype requires manual start after reboot.
- Do not run stress tests while the device is hot or charging at elevated temperature.
- Stop tests if Android reports severe thermal state, battery swelling, unusual odor, or abnormal device behavior.
- Do not estimate player capacity until repeated load tests on the exact device, Java runtime, server build, world, and plugin set have been completed.

## MKPHost deployment gate

Before producing an MKPHost-specific installation package, confirm:

- Supported server software/fork and version.
- Supported Java runtime/version and CPU architecture.
- Whether custom server JAR upload is supported.
- Whether startup command and JVM flags can be configured.
- Available RAM, storage, and player/network limits.
- How backups and rollback are performed in the host panel.

Deployment is allowed only after the user confirms the target instance, a verified backup exists, and the exact release candidate has passed the test checklist. This document does not authorize access to or changes on the user's hosting account.
