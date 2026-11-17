# NexoraAI foundation

NexoraAI is the planned in-core assistant and operations observer for NEXORA.

## First foundation: read-only diagnostics

`ru.nexora.ai.NexoraAiDiagnostics` exposes a point-in-time JVM snapshot:
- heap usage and configured heap maximum;
- live thread count and available processor count;
- system load average when the JVM provides it;
- process uptime;
- failures explicitly reported by core subsystems.

The first version is intentionally read-only. It does not restart services, mutate worlds,
change configuration, edit source files, execute shell commands, or make network requests.
A warning is a signal for an operator, not permission for an automatic repair.

## Planned stages

1. Read-only runtime diagnostics and bounded failure summaries.
2. A server-owned event/metrics feed with subsystem-specific health checks.
3. Authenticated in-game `/ai` chat and an operator-only `/ai admin` view.
4. Optional model-provider adapter, configured only through server environment secrets.
5. Guarded repair proposals with tests, backups, explicit operator approval, and rollback.

No model API key or provider integration was found in the current source tree during the initial
repository review. The GitHub repository connection is not itself a model-provider credential;
the adapter will be wired only after the existing deployment configuration is identified.
