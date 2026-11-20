# NEXORA Third-Party Plugin Security Policy

Status: policy/design draft; enforcement is not yet implemented.

## Policy

NEXORA permits third-party plugins, subject to administrator review and the security controls supported by the installed NEXORA version. Plugin authors retain ownership of their own code and choose their own license. Submitting a plugin does not transfer copyright to NEXORA.

## Intake and review

Before a plugin is enabled on a production server:
1. Identify its author, source repository, version, declared license, and supported NEXORA API versions.
2. Verify the download source and calculate a SHA-256 checksum.
3. Inspect its manifest and declared capabilities.
4. Scan the artifact and dependencies with maintained malware/vulnerability scanners.
5. Review dependency licenses and known vulnerabilities.
6. Test in a disposable staging server with no production credentials or world data.
7. Require an explicit server-owner approval and record who approved which exact checksum.
8. Quarantine failed, unsigned-when-signatures-are-required, or unreviewed artifacts.

Automated scanning is a signal, not proof that a plugin is safe. A clean scan cannot guarantee the absence of malicious behavior.

## Plugin manifest

A future manifest should declare:
- plugin ID, display name, version, author and contact;
- plugin entry point;
- supported NEXORA API range;
- source URL and license identifier;
- SHA-256 artifact checksum;
- required and optional capabilities;
- declared network destinations, if any;
- data/storage paths used;
- whether the plugin requires native libraries or subprocesses.

The loader must reject malformed manifests, duplicate plugin IDs, incompatible API ranges, path traversal, and checksum mismatches.

## Permissions and trust boundaries

Potential capabilities include command registration, event subscriptions, world read/write, player data access, filesystem access, outbound network access, and process execution. Grant only the capabilities the plugin needs and log grants and changes.

Important: ordinary Java code running inside the NEXORA JVM can often bypass a cooperative permission API and access process resources directly. Do not describe in-process plugins as sandboxed. If a plugin is not fully trusted, do not load it in-process. Run it in a separate process or container with OS-level restrictions and communicate through a narrow authenticated IPC protocol.

## Runtime controls

- Keep plugin data in a dedicated directory; normalize paths and prevent traversal.
- Provide enable/disable controls and a safe-mode startup option.
- Record plugin load, unload, crash, permission, and network-policy events.
- Apply resource limits at the process/container level for untrusted plugins.
- Keep plugin installation separate from core updates.
- Never let a plugin modify or replace the core license verification keys.
- Back up worlds and plugin data before production upgrades.

## Reporting

Provide a security contact and a process for reporting vulnerabilities. A reported plugin should be quarantined pending investigation where credible evidence indicates active harm.

## Compatibility and responsibility

The server owner is responsible for approving plugins installed on their server. NEXORA should provide clear metadata and warnings, but must not promise that automated checks make arbitrary plugin code safe.
