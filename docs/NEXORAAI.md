# NexoraAI

NexoraAI is an in-core operations observer and optional in-game assistant for NEXORA.

## Runtime diagnostics

`ru.nexora.ai.NexoraAiDiagnostics` provides a read-only JVM snapshot:
- heap usage and configured maximum;
- live thread count and available processors;
- system load average when provided by the JVM;
- process uptime;
- bounded summaries of failures explicitly reported to the service.

`NexoraAiMonitor` samples these metrics every 30 seconds and writes warnings to the server console. It does not change server state.

## In-game assistant

- `/ai` opens a Bedrock input form.
- `/ai <question>` sends a direct question.
- `/ai help` lists available commands; `/ai status` shows whether the provider is configured.
- The assistant answers asynchronously through an OpenAI-compatible Chat Completions endpoint.
- Per-player requests are rate-limited and prompts/replies are bounded.
- The model receives a small read-only JVM status snapshot. It is explicitly instructed not to claim it can execute commands or modify the server.

Configuration is read from the server process environment:
- `NEXORA_AI_API_KEY` — provider key; without it the chat stays disabled.
- `NEXORA_AI_MODEL` — optional model name; default `gpt-4o-mini`.
- `NEXORA_AI_ENDPOINT` — optional full Chat Completions URL; default OpenAI endpoint.
- `NEXORA_AI_TIMEOUT_SECONDS` — request timeout; default 30.
- `NEXORA_AI_ADMIN_XUIDS` — comma-separated Xbox XUID allowlist for operator mode.

Only HTTPS endpoints are accepted, except HTTP on localhost for private development providers. API keys are never written to source files or logs.

## Operator mode

- `/ai admin` shows a read-only runtime snapshot.
- `/ai admin <question>` asks the assistant in operator context.
- Access is granted only when the authenticated Xbox XUID is present in `NEXORA_AI_ADMIN_XUIDS`.
- This mode does not execute commands, change settings, edit files, restart services, or mutate worlds.

## Safety and roadmap

The current release is observe-and-explain only. Automated source repairs are not enabled. A future repair workflow must create a proposal, run tests, preserve a backup, require explicit operator approval, and support rollback before any change can be applied.
