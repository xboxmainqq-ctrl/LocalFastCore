# NEXORA Security Architecture

## Goals

Protect the NEXORA source, release artifacts, server data, and operator access while preserving the compatibility layer for Nukkit-MOT and legacy plugins.

Security is layered. No single check makes a Java server or plugin impossible to copy, modify, or compromise. In particular, plugins running inside the same JVM are not a security sandbox: a malicious or compromised plugin can generally access process memory, server APIs, and files available to the server account.

## Security principles

- Fail safely: integrity warnings must not automatically delete data, reset worlds, or terminate a healthy server.
- Least privilege: CI jobs, operators, service accounts, and plugins receive only the access they need.
- Verify before trust: release artifacts must have a checksum and, when signing is introduced, a verifiable signature.
- Keep secrets out of source and JAR files. Never embed signing private keys, tokens, passwords, or FTP credentials in the project.
- Preserve compatibility: do not rename legacy plugin IDs, permissions, commands, package names, or data directories as part of security work.
- Make security events auditable without collecting unnecessary player data.

## Threat model

This project considers accidental or malicious source changes, compromised dependencies, altered release JARs, stolen operator credentials, unsafe server configuration, destructive plugin behavior, and loss of server data.

It does not claim to prevent reverse engineering, copying of distributed Java bytecode, or malicious code running with the same operating-system permissions as the server.

## Layer 1 — Source repository

Repository owners should configure these settings in GitHub (settings are account/repository controls and are not changed by this source commit):

1. Keep the repository private if the project is not intended to be public.
2. Require two-factor authentication for every account with write access.
3. Protect `work/0.44.0` and `nexora/**` with rulesets:
   - require pull requests before merging;
   - require the build and test checks to pass;
   - block force pushes and branch deletion;
   - restrict bypass permissions to trusted owners.
4. Review collaborators and remove unused access.
5. Store credentials only in GitHub Actions secrets or the hosting provider's secret store. Rotate credentials if they were ever committed.
6. Enable secret scanning and push protection when available on the repository plan.

## Layer 2 — Build and dependencies

- CI permissions should be read-only unless a job explicitly needs more.
- Pin third-party GitHub Actions to reviewed commit SHAs before treating the workflow as a hardened release pipeline.
- Keep dependency versions explicit and review dependency update pull requests.
- Run unit tests and compatibility checks before producing release artifacts.
- Do not publish artifacts from untrusted pull-request code with access to release secrets.
- Generate SHA-256 checksums for every distributable JAR.
- Publish NEXORA release JARs only from version tags that point to commits already merged into `nexora/compatibility-edition`.
- Sign each release JAR and checksum manifest using Sigstore keyless signing with GitHub Actions OIDC; publish the verification bundles with the release assets.
- Verify the certificate identity, OIDC issuer, Sigstore bundle, and SHA-256 checksum before installation. No private signing key is stored in the repository or embedded in a JAR.

## Layer 3 — Runtime and server host

- Run the server as a dedicated unprivileged operating-system account, never as root/Administrator.
- Restrict inbound ports with a firewall; do not expose SSH, RCON, database, or management interfaces publicly unless required and secured.
- Use SSH keys and disable password-based remote administration where supported.
- Keep Java, the host OS, Nukkit-MOT, and dependencies patched.
- Keep backups outside the server's writable directory and test restoration regularly.
- Restrict write access to the server directory and plugin configuration to trusted operators.
- Keep FTP credentials private; prefer SFTP/SSH where the host supports it.

## Layer 4 — NEXORA runtime security

Implemented runtime security foundation:

- `NexoraSecurityManager`: coordinates integrity checks and audit events; registered as a NEXORA service.
- `NexoraAccessController`: checks module-supplied permission nodes through a neutral `PermissionSubject`, records grant/deny decisions, and propagates audit write failures.
- `NukkitPlatformAdapter`: adapts Nukkit-MOT `CommandSender.hasPermission(...)` to `PermissionSubject` without caching decisions.
- `IntegrityChecker`: computes SHA-256 for regular files, rejects symbolic links, and never modifies the checked file.
- `AuditLogger`: appends escaped JSON-lines events to the security audit log.

Still planned:

- `SignatureVerifier`: local verification of release bundles or trusted manifests where offline verification is required. Release signing currently happens in GitHub Actions through Cosign/Sigstore, not inside the server plugin.
- Per-module permission-node registration and command/action integration. Each module must call `security().access().authorize(...)` before performing a protected action.
- `BackupManager`: coordinates safe backup/restore workflows with explicit operator approval.

The runtime foundation is report-and-audit only. It must not silently delete, rewrite, quarantine files, disable plugins, or stop the server after an integrity mismatch. Any future enforcement mode must be explicitly configured, tested on a separate server copy, and preserve a recovery path.

## Compatibility and trust boundaries

Nukkit-MOT remains the host and legacy plugin loader during the compatibility phase. Existing plugins execute in the same JVM and are therefore trusted code. NEXORA permissions can protect NEXORA commands and APIs, but cannot sandbox arbitrary third-party Java plugins.

Ordinary Bedrock clients must continue to connect through the compatible server path. Client-only NEXORA features must not be treated as server-side security controls.

## Rollout plan

1. Repository access review, branch protection, secret hygiene, and dependency update automation.
2. CI hardening, repeatable builds, checksums, and artifact provenance.
3. Keyless-signed release JARs and checksum manifests using Sigstore; verify before installation.
4. Runtime audit log and non-destructive integrity reporting.
5. Audited permission gate and Nukkit-MOT sender bridge; module-by-module adoption remains required.
6. Backup verification and tested recovery procedures.
7. Enforcement only after false-positive testing on a copy of the server.

## Release workflow activation

The release workflow is stored at `.github/workflows/release-nexora.yml` on both `main` and `nexora/compatibility-edition`. GitHub requires a workflow file to exist on the default branch for tag-triggered workflows to run; the workflow has now been added to `main`. The release job independently checks that the tagged commit is already an ancestor of `nexora/compatibility-edition`.

## Operational rule

Never deploy a development build or enable enforcement on the live server without an explicit operator decision, a verified backup, and a successful test on a separate copy.
