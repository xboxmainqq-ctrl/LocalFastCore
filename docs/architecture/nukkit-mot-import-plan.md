# Nukkit-MOT import and build integration plan

**Status:** preparation only; upstream source is not yet present in this repository.
**Pinned upstream:** `MemoriesOfTime/Nukkit-MOT`
**Pinned revision:** `3258c14baf71792c289519d623ef14e7cc02dd37`
**License:** LGPL-3.0 (retain upstream notices and comply with source/distribution obligations).

## Verified build facts

The pinned upstream `pom.xml` identifies the project as `cn.nukkit:Nukkit:MOT-SNAPSHOT`, uses Maven, and sets Java compiler source/target to 17. It also declares a reproducible output timestamp based on Git commit time. These are facts from the pinned upstream POM, not assumptions about the current NEXORA root project.

The current NEXORA root `pom.xml` is a separate Java 25 Maven project named `nexora-runtime` with main class `ru.nexora.runtime.NexoraMain`. It belongs to the preserved Standalone Runtime experiment. Replacing that POM with Nukkit-MOT's POM would break or obscure the existing experiment and would conflate two distinct build products.

## Safe repository layout

After the NEXORA-owned fork/import is available, keep the two projects isolated:

- `server/nukkit-mot/` — pinned Nukkit-MOT source tree and its upstream Maven build.
- `standalone-runtime/` (or the existing root layout until a separately tested move) — preserved NEXORA Standalone Runtime.
- `nexora-modules/` — future NEXORA-owned extensions, added only after the baseline server builds and starts.
- `docs/architecture/` — architecture decisions, compatibility evidence and migration notes.

Do not move the existing root project as part of the source import unless a dedicated change first proves that all current build/test workflows still pass.

## Import procedure

1. Create a NEXORA-owned fork of the pinned upstream, or import its full Git history into a dedicated directory/module. Prefer a fork when possible so upstream merges remain explicit.
2. Preserve upstream commit history, license files, copyright headers and notices. Record the exact upstream remote and pinned revision.
3. Do not rewrite upstream package names or mass-rename classes during the initial import.
4. Add a small NEXORA-owned overlay only after the untouched upstream source builds. Keep upstream changes separate and reviewable.
5. If using a vendored source tree rather than a fork, document how upstream security fixes and releases will be merged.
6. Do not copy the Standalone Runtime's Bedrock/resource-pack/network code into Nukkit-MOT without first mapping it to real Nukkit-MOT APIs.

## Build and acceptance gates

### Gate A — source provenance
- [ ] NEXORA-owned fork/import exists and is accessible to CI.
- [ ] Pinned upstream SHA is recorded in repository metadata.
- [ ] LGPL-3.0 license and upstream notices are present.
- [ ] No unrelated Standalone Runtime files were overwritten.

### Gate B — reproducible baseline
- [x] A manually triggered GitHub Actions workflow checks out the pinned Nukkit-MOT revision into an isolated CI workspace: `.github/workflows/nukkit-mot-baseline.yml`.
- [ ] CI baseline workflow has completed successfully and produced a verified artifact.
- [ ] CI checks out the pinned Nukkit-MOT source as part of a successful run.
- [ ] CI builds it using the JDK/Maven requirements declared by that pinned revision.
- [ ] CI runs the upstream test suite where supported.
- [ ] CI publishes a clearly labelled test artifact with commit SHA and build metadata.
- [ ] A clean checkout can reproduce the build without relying on a developer's local files.

### Gate C — host/client acceptance
- [ ] Install the generated artifact on McpeHost.
- [ ] Start the server and retain startup logs.
- [ ] Connect using an unmodified retail Minecraft Bedrock 1.26.60 client.
- [ ] Verify login, spawn, movement, chunk loading and disconnect/reconnect.
- [ ] Record host settings, server artifact SHA, client version and test outcome.
- [ ] Keep a known-good rollback artifact/configuration.

Do not mark Gate C complete based only on CI or a protocol-version declaration.

## First NEXORA-owned code changes after Gate B

Make small, isolated changes in this order:

1. NEXORA product metadata, startup banner and default configuration.
2. NEXORA-owned extension API/module registration, without changing packet handling.
3. Player identity and permission integration, with migration-safe storage.
4. Staff commands and audit logging.
5. RPG classes, stats, abilities and progression as modules.
6. Core patches only where the extension API demonstrably cannot support a required feature.

Every core patch must include tests, a short rationale, an upstream-sync note and a rollback path.

## Current blockers and honest status

- The connected upstream access is read-only; it cannot create or push a NEXORA fork.
- No Nukkit-MOT source import is claimed yet; the workflow fetches upstream into an ephemeral CI workspace only.
- The baseline workflow is committed but has not yet been run; no Nukkit-MOT build artifact or McpeHost/retail-client acceptance result is claimed yet.
- The Standalone Runtime remains preserved and is not the active hosted server foundation.
