# NexoraCore — Compatibility Edition

NexoraCore is the first isolated foundation module for the NEXORA server project.

## Current stage

This is a Nukkit-MOT plugin targeting Java 17 bytecode and built in CI with JDK 21 against Nukkit-MOT 1.26.60-R1. Current Nukkit-MOT setup documentation recommends Java 21 or newer for the server runtime. It is not yet a standalone server fork. Nukkit-MOT continues to load legacy plugins while NEXORA APIs are introduced additively.

## Included foundation

- Plugin bootstrap and lifecycle handling.
- Typed, thread-safe service registry.
- Public `NexoraAPI` entry point for future modules.
- Synchronous typed event bus with removable subscriptions.
- Player join/quit lifecycle bridge, isolated under the Nukkit integration package, that publishes UUID and current name without exposing mutable server objects.
- Platform-neutral `NexoraPlatform` contract and immutable `PlayerSnapshot` model.
- Nukkit-MOT adapter that provides online-player snapshots and UUID lookup through the platform contract.
- Namespaced persistent key-value storage, saved atomically to the plugin data folder.
- Player profile model and repository contract.
- Security services exposed through `NexoraAPI.security()`: SHA-256 file integrity checks, JSON-lines audit logging, and an audited NEXORA access controller for sensitive module actions.
- Nukkit-MOT permission bridge that adapts `CommandSender.hasPermission(...)` into the platform-neutral `PermissionSubject` contract without caching permission decisions.
- JUnit tests for core utility and security APIs.

## Security behavior

The runtime integrity layer is deliberately report-only. Permission checks are explicit gates that modules must call before sensitive actions:

- `IntegrityChecker` computes SHA-256 digests for regular files and rejects symbolic-link targets.
- `NexoraSecurityManager` compares a file against an expected digest and records the result.
- `NexoraAccessController` checks a permission through a supplied `PermissionSubject`, audits both grants and denials, and propagates audit I/O failures so callers can fail closed.
- `NukkitPlatformAdapter.permissionSubject(sender)` delegates each permission check to Nukkit-MOT; `actorLabel(sender)` supplies a bounded label for audit events.
- `AuditLogger` appends JSON-lines events to `plugins/NexoraCore/security-audit.jsonl`.
- Integrity mismatches are audited; files are not deleted, quarantined, or modified, and the server is not stopped automatically.
- Permission decisions are not a sandbox for third-party plugins. NEXORA modules must call the access controller before each protected operation and must not continue when authorization is denied or audit persistence fails.

The audit log is local operational data. Protect it with operating-system file permissions and include it in a secure backup policy. Callers must not put passwords, access tokens, or other secrets in audit details.

These checks do not sandbox arbitrary Java plugins running in the same JVM. A compromised plugin with the server process's permissions may still access files and APIs available to that process.

## Compatibility rules

- Existing plugins continue to be loaded by Nukkit-MOT.
- This module does not rewrite, rename, or modify existing plugin JARs.
- Keep the `LocalFastCore` plugin ID, commands, permissions, Java packages, and data paths unchanged while ClassAbilities depends on it.
- Compile success is not runtime compatibility. Test the exact server build and installed plugin set on a copy before deployment.

## Build

From the repository root:

    mvn -f nexora-core/pom.xml --batch-mode --no-transfer-progress clean verify

Output JAR:

    nexora-core/target/NexoraCore-0.1.0-SNAPSHOT.jar

## Releases and artifact verification

Tagged releases use the Sigstore release workflow at `.github/workflows/release-nexora.yml`. It builds and tests the versioned artifact, creates a SHA-256 manifest, signs both the JAR and manifest using GitHub Actions OIDC, verifies the generated bundles, and publishes them as GitHub release assets.

The release workflow is also installed on the repository's default branch (`main`), as GitHub requires tag-triggered workflow files to exist there. The release job also checks that the tag points to a commit already contained in `nexora/compatibility-edition`.

See [NEXORA_RELEASE_VERIFICATION.md](../NEXORA_RELEASE_VERIFICATION.md) for installation-side checksum and signature verification commands.

## Next milestones

1. Verify the Nukkit-MOT adapter and runtime security services on the target server copy.
2. Complete and integrate durable player profile persistence with safe asynchronous access.
3. Add signed-manifest verification for configured runtime files, with report-only behavior by default.
4. Add protocol negotiation and client capability handshake.
5. Add NexoraUI abstractions with a fallback for ordinary Bedrock clients.
6. Expand automated compatibility checks for LocalFastCore and ClassAbilities.
7. Evaluate a maintained server fork only after the compatibility layer is stable.

Do not install this development snapshot on the live server. Back up the server and test on a copy first.

## Platform independence

The `ru.nexora.core.platform` package defines platform-neutral contracts. New public APIs should use NEXORA-owned immutable models such as `PlayerSnapshot`, never expose Nukkit classes or live platform objects. The current Nukkit-MOT bootstrap and listener bridge are the host adapter. See [NEXORA_STANDALONE_ARCHITECTURE.md](../NEXORA_STANDALONE_ARCHITECTURE.md) for the staged migration plan and removal criteria.
