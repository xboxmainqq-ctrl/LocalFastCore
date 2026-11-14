# NEXORA Control Plane Architecture

## Purpose

NEXORA is intended to grow from a compatibility-first server core into a platform for operating multiple game servers from one control panel. The control plane is a separate product layer; it is not embedded into the Nukkit-MOT plugin and does not replace the game server process.

This document defines the initial architecture and safety boundaries. It does not claim that a web panel, host agent, cloud hosting service, or remote deployment implementation already exists.

## Product boundary

NEXORA has three independently deployable parts:

1. **Control plane** — the web/API service that stores server inventory, users, roles, desired configuration, job history, and audit events.
2. **Host agent** — a small service installed on each VPS or dedicated host. It maintains an outbound authenticated connection to the control plane and performs a narrow set of typed operations for servers assigned to that host.
3. **Game runtime** — Nukkit-MOT/NEXORA server instances and their plugins. Existing LocalFastCore IDs, commands, permissions, package names, and data paths remain unchanged during compatibility work.

The control plane can be self-hosted by the operator or later offered as a hosted service. NEXORA development does not itself provide physical hosting, network capacity, or an always-on cloud environment.

## High-level topology

```text
Operator browser
      |
      | HTTPS (login, MFA, session)
      v
NEXORA Control Plane
  - Web UI / API
  - Identity and role policy
  - Server inventory
  - Job queue and state
  - Audit trail
  - Secret references
      ^
      | Outbound authenticated TLS connection
      | typed requests / signed responses
      v
NEXORA Host Agent (one per machine)
  - enrollment identity
  - allowlisted server operations
  - process supervision adapter
  - filesystem boundary checks
  - host health reporting
      |
      +---- Server A (dedicated OS account)
      +---- Server B (dedicated OS account)
      +---- Server C (dedicated OS account)
```

The host agent initiates the network connection. The control plane must not require a publicly exposed agent port, SSH daemon, RCON port, or database port. SSH remains an optional operator-managed maintenance channel, not the panel's default command transport.

## Core design rules

- **Least privilege:** run the agent as a dedicated unprivileged OS account. It must not run as root/Administrator for normal operation.
- **Outbound first:** hosts establish outbound TLS connections. Do not expose a general-purpose remote shell.
- **Typed operations only:** accept a versioned operation schema such as `START_SERVER`, `STOP_SERVER`, `RESTART_SERVER`, `READ_STATUS`, `COLLECT_LOG_TAIL`, `CREATE_BACKUP`, and `APPLY_APPROVED_CONFIG`. Never accept arbitrary shell strings from the panel.
- **Per-server isolation:** each game server runs under its own OS account or equivalent isolation boundary, with explicit data and resource limits.
- **Explicit authorization:** authorize every sensitive operation against the operator, server, action, and current role. Denials and successful actions are audited.
- **No silent destructive action:** no automatic world deletion, plugin removal, rollback, or server termination on integrity mismatch or connectivity loss.
- **Idempotent jobs:** every requested operation has a unique job ID, expiry, target server, requested action, and observable terminal state. Duplicate delivery must not repeat a destructive effect.
- **Secrets stay secret:** store credentials in a dedicated secret store or host-side protected files. Never place passwords, tokens, private keys, or enrollment secrets in source code, logs, plugin configs, or JARs.
- **Compatibility first:** the panel and agent manage the existing server installation from outside the game process. They do not rewrite legacy plugin metadata or modify plugin behavior implicitly.

## Control plane responsibilities

The initial control plane should own:

- Operator authentication and session lifecycle.
- Roles such as Owner, Administrator, and Read-only Operator, with server-scoped grants.
- A server inventory containing a stable server ID, display name, assigned host, runtime type/version, installation path reference, and desired state.
- A job queue with authorization decision, creation time, expiry, agent acknowledgement, execution result, and audit correlation ID.
- Health snapshots: agent connectivity, process state, uptime, CPU/memory/disk measurements where supported, and last successful check-in.
- Configuration revisions and explicit approval before applying a change.
- Backup catalog metadata and verification status; backup bytes remain on a separately protected storage target.
- Audit search and export with bounded retention and access controls.

The control plane should not store raw player chat or collect unnecessary player personal data.

## Host agent responsibilities

The agent should:

- Enroll using a one-time, short-lived enrollment code that is exchanged for a host identity.
- Authenticate each connection with a host-specific certificate or equivalent managed identity; support rotation and revocation.
- Validate request schema, target server ownership, operation allowlist, request expiry, and job ID before execution.
- Map server IDs to administrator-configured directories and service units. Never trust a path supplied freely by a remote request.
- Execute only fixed, reviewed process-supervision and filesystem operations.
- Enforce per-server directory boundaries; reject path traversal, symlinks where unsafe, and unexpected ownership.
- Return structured results and bounded log excerpts, not unrestricted filesystem access.
- Keep a small local journal so a reconnect does not accidentally repeat an already completed job.
- Continue running game servers if the control plane becomes unavailable. Loss of panel connectivity must not stop healthy game processes.

## Security model

### Trust boundaries

- The browser is untrusted until authenticated.
- The control plane is trusted to authorize operators but is not automatically trusted to execute host commands.
- Each host agent trusts only authenticated, unexpired, correctly scoped control-plane requests.
- A game server and its plugins are not a security sandbox. Java plugins running in the same JVM may access process memory and files available to the server account.
- A compromised game plugin must not gain control-plane credentials or the host agent's identity merely because it runs on the same machine.

### Required controls before production

- MFA for privileged operator accounts.
- Server-scoped RBAC and deny-by-default authorization.
- TLS in transit and host-specific authenticated identity.
- One-time enrollment, credential rotation, and immediate revocation.
- Replay protection, short request expiry, unique job IDs, and idempotency.
- Append-only or tamper-evident audit storage with restricted access.
- Rate limits and bounded payload/log sizes.
- Host firewall rules and dedicated OS accounts.
- Dependency and release provenance checks for the panel and agent.
- Tested backups and recovery procedures.
- Security review of every new agent operation.

### Explicit non-goals

The first version is not a general cloud provider, virtual machine hypervisor, container orchestrator, billing system, DDoS protection service, or guarantee of protection against a compromised host administrator. Those capabilities require separate infrastructure and security design.

## Initial delivery stages

1. **Contracts and threat model:** define server identity, host identity, typed job envelope, status model, RBAC actions, and audit event schema.
2. **Local host agent prototype:** manage one test server on one machine using a fixed allowlist; no public remote access and no arbitrary shell.
3. **Control-plane API:** authenticate a local operator, register one host, list servers, enqueue a typed status/start/stop job, and display its result.
4. **Web panel:** server list, health/status view, console log tail, and explicit start/stop/restart controls with confirmation.
5. **Safe operations:** configuration revisions, plugin inventory, verified backup creation, and recovery workflow after separate testing.
6. **Multi-host operations:** enrollment, revocation, host health, scoped roles, and resilient job delivery.
7. **Production readiness:** threat review, penetration testing, rate limits, operational runbooks, monitoring, and documented recovery drills.

Each stage must build and test independently. Do not connect the prototype to a live production server until it has been tested against disposable server instances and the operator explicitly approves deployment.

## Compatibility with NexoraCore

NexoraCore remains the in-game compatibility/runtime module. The control plane and host agent must communicate through versioned external contracts and must not introduce a hard runtime dependency from legacy plugins to the panel. Existing Nukkit-MOT loading behavior and LocalFastCore compatibility identifiers remain intact.

The current NexoraCore backup foundation is a local, explicit directory snapshot API. It is not yet a remote backup service, scheduler, manifest verifier, or restore system. The control plane must not present those capabilities as available until they are implemented and tested.
