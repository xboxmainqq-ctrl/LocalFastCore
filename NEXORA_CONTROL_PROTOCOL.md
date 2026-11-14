# NEXORA Control Protocol — Initial Contract

## Status

This is a versioned design contract for the first control-plane/host-agent prototype. It is not yet an implemented network protocol. Implementations must reject unknown protocol major versions and unknown operation types.

## Transport

- The host agent initiates an outbound TLS connection to the configured control-plane endpoint.
- Production connections require mutual authentication using a host-specific identity.
- A host identity is scoped to one registered host and can be revoked independently.
- Enrollment codes are one-time, short-lived, and exchanged only over TLS. They must never be written to logs.
- The protocol does not expose an inbound agent listener by default.
- Messages have strict maximum sizes and UTF-8 JSON encoding. Binary backup transfer is a separate, authenticated, resumable channel and is not part of the first prototype.

## Common message envelope

Every control request uses the following logical fields:

| Field | Meaning |
| --- | --- |
| `protocolVersion` | Version string, initially `1.0` |
| `messageId` | Unique message identifier |
| `jobId` | Unique operation/job identifier |
| `issuedAt` | UTC timestamp |
| `expiresAt` | UTC expiry timestamp |
| `hostId` | Registered destination host |
| `serverId` | Registered destination server, when applicable |
| `operation` | Exact allowlisted operation name |
| `parameters` | Operation-specific validated object |
| `actorId` | Authenticated operator/service identity |
| `authorizationId` | Control-plane authorization decision reference |

Example status request:

```json
{
  "protocolVersion": "1.0",
  "messageId": "msg_01JEXAMPLE0001",
  "jobId": "job_01JEXAMPLE0001",
  "issuedAt": "2026-11-14T12:00:00Z",
  "expiresAt": "2026-11-14T12:01:00Z",
  "hostId": "host_eu_01",
  "serverId": "server_lobby",
  "operation": "READ_STATUS",
  "parameters": {},
  "actorId": "operator_01",
  "authorizationId": "authz_01JEXAMPLE"
}
```

The example IDs are illustrative only. Implementations must generate cryptographically unpredictable identifiers where secrecy or uniqueness is required.

## Initial operation allowlist

| Operation | Required scope | Parameters | Initial behavior |
| --- | --- | --- | --- |
| `READ_STATUS` | `server.read` | none | Return process and health state |
| `COLLECT_LOG_TAIL` | `server.logs.read` | bounded line count | Return a bounded tail from the configured log |
| `START_SERVER` | `server.start` | none | Start the mapped server service |
| `STOP_SERVER` | `server.stop` | bounded graceful timeout | Request graceful stop, then report whether it completed |
| `RESTART_SERVER` | `server.restart` | bounded graceful timeout | Stop and start through the service supervisor |
| `CREATE_BACKUP` | `server.backup.create` | registered backup profile ID | Create an explicit backup using a configured path/profile |

No arbitrary command, shell string, executable path, environment variable, file path, plugin upload, or SQL query is accepted in the first protocol version. Additional operations require a protocol review, explicit permission scope, tests, and documentation.

## Job state machine

A job moves through these states:

```text
QUEUED -> DELIVERED -> ACCEPTED -> RUNNING -> SUCCEEDED
                                      |-----> FAILED
                                      |-----> CANCELLED (only before execution)
QUEUED / DELIVERED / ACCEPTED -> EXPIRED
```

- State changes are monotonic; a terminal state cannot be reopened.
- The control plane stores every transition with a timestamp and audit correlation ID.
- The agent acknowledges a job before execution.
- The agent records the job ID and result locally so redelivery does not repeat a completed action.
- Expired requests are rejected, not executed late.
- A lost acknowledgement is resolved by querying job state, not by issuing a new job with a different ID.
- Cancellation is not promised once an operation is running. Stop/restart operations must report their actual process outcome.

## Response envelope

An agent response includes:

- `protocolVersion`, `messageId`, and `jobId`.
- `hostId` and optional `serverId`.
- `state` and `completedAt`.
- A stable machine-readable `resultCode`.
- A bounded, non-secret `summary`.
- A structured `data` object matching the operation schema.

Example:

```json
{
  "protocolVersion": "1.0",
  "messageId": "msg_01JEXAMPLE0002",
  "jobId": "job_01JEXAMPLE0001",
  "hostId": "host_eu_01",
  "serverId": "server_lobby",
  "state": "SUCCEEDED",
  "completedAt": "2026-11-14T12:00:08Z",
  "resultCode": "SERVER_STATUS_RETURNED",
  "summary": "Status collected",
  "data": {
    "processState": "RUNNING",
    "uptimeSeconds": 3600
  }
}
```

Never include credentials, access tokens, private keys, full environment dumps, or unrestricted filesystem paths in a response.

## Validation and replay protection

Before accepting a request, the agent must:

1. Authenticate the control-plane peer and validate the host identity.
2. Check protocol compatibility and message size.
3. Confirm the request host ID matches this agent.
4. Confirm the server ID is assigned to this host.
5. Validate timestamps and reject expired or excessively future-dated messages.
6. Validate the job ID and reject conflicting reuse of an existing ID.
7. Check the operation against the exact allowlist and validate all parameters.
8. Verify the authorization reference and scope using an authenticated, integrity-protected control-plane assertion.
9. Persist the job acceptance record before performing the operation.
10. Audit the decision and outcome without recording secrets.

The agent must fail closed if authorization cannot be validated. A network outage must not stop already-running game server processes.

## Authorization scopes

Initial scope names:

- `host.read`
- `server.read`
- `server.logs.read`
- `server.start`
- `server.stop`
- `server.restart`
- `server.backup.create`

Permissions are evaluated for the specific operator, host, server, and operation. A role name alone is not sufficient authorization. The Owner role may administer assignments, but every action is still audited.

## Error codes

Initial stable result codes:

- `UNSUPPORTED_PROTOCOL`
- `INVALID_MESSAGE`
- `MESSAGE_EXPIRED`
- `HOST_MISMATCH`
- `SERVER_NOT_ASSIGNED`
- `UNKNOWN_OPERATION`
- `INVALID_PARAMETERS`
- `AUTHENTICATION_FAILED`
- `AUTHORIZATION_DENIED`
- `DUPLICATE_JOB`
- `JOB_ALREADY_TERMINAL`
- `SERVER_NOT_FOUND`
- `PROCESS_OPERATION_FAILED`
- `FILESYSTEM_BOUNDARY_VIOLATION`
- `AGENT_INTERNAL_ERROR`

Error summaries must be safe for display and must not leak stack traces, secrets, or sensitive host configuration.

## First prototype acceptance criteria

The first implementation is limited to a disposable test host and one test server:

- Enroll one agent with a one-time code.
- Revoke that agent identity and prove subsequent requests fail.
- List the registered server and read its status.
- Start, stop, and restart only the server mapped in local agent configuration.
- Reject unknown operations, arbitrary shell parameters, path traversal, expired messages, wrong-host messages, and unauthorized jobs.
- Redeliver a completed job and prove it is not executed twice.
- Disconnect the control plane and prove the running game server remains running.
- Produce auditable records for grant, denial, acceptance, and completion.
- Run automated tests for validation and job-state transitions.

No live server deployment is part of this milestone.
