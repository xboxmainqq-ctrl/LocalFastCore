# NexoraAI

NexoraAI is an in-core operations observer and optional in-game assistant for NEXORA.

## Runtime diagnostics

`ru.nexora.ai.NexoraAiDiagnostics` provides a read-only JVM snapshot:
- heap usage and configured maximum;
- live thread count and available processors;
- system load average when provided by the JVM;
- process uptime;
- bounded summaries of failures explicitly reported to the service;
- a newest-first ring buffer of up to 12 timestamped failure/slow-operation events, with descriptions capped at 240 characters and common bearer/API/access-token patterns redacted; repeated failure signatures are grouped by subsystem and exception type, and slow operations by subsystem. Signature storage is capped at 16 groups.

`NexoraAiMonitor` samples these metrics every 30 seconds and writes warnings to the server console. It does not change server state. The Bedrock mob-AI update and furnace tick are timed; executions taking longer than 50 ms are counted. Periodic world/player autosaves taking longer than 1 second are also counted. Bedrock inbound message handling is also timed from before per-session lock acquisition through packet dispatch; processing taking longer than 50 ms is grouped as `bedrock-inbound`. This includes time waiting for that session's lock and is not a packet-rate or network-bandwidth measurement. The current core does not expose one centralized fixed-rate server tick loop: player movement is processed from Bedrock `PlayerAuthInput`, while mob AI is throttled to at most one update per 50 ms when player input is handled. Therefore inbound processing latency is measured as its own signal rather than mislabeled as TPS. These are subsystem execution warnings, not a server-wide TPS measurement or a complete hang detector.

## NexoraAI Guard (network observer)

- The first Guard stage observes inbound NetherNet transport frames per peer using monotonic 5-second windows.
- Default review thresholds are more than 300 frames or more than 1 MiB per peer per window. A peer produces at most one signal per window.
- Per-peer state is capped at 1,024 entries and removed on disconnect; when full, the oldest tracked entry is evicted.
- Aggregate Guard status is included in the operator runtime context without exposing peer identifiers. Threshold crossings are also added to the bounded diagnostic evidence ring as identifier-free `GUARD` events for operator status and repair-plan context.
- Signals are evidence for review only. This stage does not throttle, disconnect, mute, or ban players.
- These are application-observed per-peer frame/byte thresholds, not proof of a DDoS attack. They cannot observe traffic dropped upstream or protect a saturated network link; host/network-level filtering is still required.
- When server-authoritative movement is enabled, Guard observes client/server reconciliation errors. Four horizontal discrepancies above 0.75 blocks in a 10-second window produce a possible-speed review signal; the same threshold for vertical discrepancies produces a possible-fly signal.
- Player-versus-player and player-versus-mob attack attempts beyond the server weapon reach by more than 0.75 blocks are counted; five such attempts in a 10-second window produce a possible-reach review signal.
- Movement and reach evidence is bounded to 1,024 player keys, reset by a 10-second monotonic window, and cleared when the authenticated player disconnects. Aggregate status reports tracked and suspicious players without exposing XUIDs.
- These signals are heuristics, not proof: latency, prediction differences, server load, or legitimate edge cases can cause movement corrections; repeated out-of-range attempts can also be accidental. Review evidence before taking action.
- KillAura review detects a high-rate multi-target pattern only after 65 server-validated hits and at least 4 target switches in a 5-second monotonic window. PvP hits are recorded only after cooldown, reach, line-of-sight, and damage checks; mob hits are recorded after the authoritative world damage operation succeeds. Each detection receives a case ID (`K-000001` etc.); up to 64 cases are kept in memory for 30 minutes, including multiple cases for one player. Player names are sanitized and XUIDs/target identifiers are never shown in the panel. `/ai guard` is restricted to the existing authenticated operator XUID allowlist. `/ai guard cases` lists recent cases, `/ai guard case <ID>` shows evidence, `/ai guard confirm <ID>` records a moderator-confirmed case, and `/ai guard dismiss <ID>` marks a false positive. A decision is recorded once and cannot be overwritten; the private reviewer XUID is retained only in memory and is not displayed. Confirmation does not execute a ban, kick, mute, or any other punishment. Cases are heuristics for moderator review, not proof of cheating; no automatic punishment is applied. The review list is memory-only and is cleared on server restart.

## In-game assistant

- `/ai` opens a Bedrock input form.
- `/ai <question>` sends a direct question.
- `/ai help` lists available commands; `/ai status` shows whether the provider is configured; `/ai clear` clears the current player's conversation context.
- The assistant answers asynchronously through an OpenAI-compatible Chat Completions endpoint.
- Per-player requests are rate-limited and prompts/replies are bounded.
- The assistant keeps up to 8 completed exchanges per authenticated player for 15 minutes of inactivity; player and operator histories are separate. Context is memory-only and is not written to disk.
- The model receives a small read-only JVM status snapshot. Operator requests also include aggregate player/session, living-entity, and dropped-item counts, without player inventory or private profile data. It is explicitly instructed not to claim it can execute commands or modify the server.

Configuration is read from the server process environment:
- `NEXORA_AI_API_KEY` — provider key; without it the chat stays disabled.
- `NEXORA_AI_MODEL` — optional model name; default `gpt-4o-mini`.
- `NEXORA_AI_ENDPOINT` — optional full Chat Completions URL; default OpenAI endpoint.
- `NEXORA_AI_TIMEOUT_SECONDS` — request timeout; default 30.
- `NEXORA_AI_ADMIN_XUIDS` — comma-separated Xbox XUID allowlist for operator mode.

Only HTTPS endpoints are accepted, except HTTP on localhost for private development providers. API keys are never written to source files or logs. Player questions, operator repair-plan descriptions, the player's display name, bounded recent conversation context, and (for repair plans) up to 12 timestamped diagnostic summaries and bounded repeat counts (with common credential patterns redacted) are sent to the configured AI provider; operators should disclose this to players and must not invite them to submit passwords, tokens, or other secrets.

## Operator mode

- `/ai admin` shows a read-only runtime snapshot and the newest bounded diagnostic events. It also includes aggregate player/session, living-entity, and dropped-item counts; no player inventory or private profile data is exposed.
- `/ai admin <question>` asks the assistant in operator context.
- Access is granted only when the authenticated Xbox XUID is present in `NEXORA_AI_ADMIN_XUIDS`.
- `/ai admin plan <problem>` asks the configured provider for a repair plan using the operator's description plus up to 12 recent timestamped diagnostic summaries. The model is told to separate observed facts from hypotheses and unknowns; `/ai admin proposals` lists saved proposals; `/ai admin proposal <id>` shows one; `/ai admin approve <id>` approves it for manual review; `/ai admin reject <id>` rejects it.
- Proposals are bounded, held in memory only, and expire after 24 hours. The proposal text and issue description are visible to allowlisted operators on the server. Approval is a review-state change only and records the reviewing operator XUID and timestamp; it does not execute commands, apply patches, edit files, restart services, or mutate worlds.

## Safety and roadmap

The current release is observe-and-explain plus non-executable repair planning. NexoraAI can draft a proposed investigation and validation/backup/rollback checklist, but it does not inspect or modify source files, run tests, create backups, or apply changes. Operator approval only marks a proposal for manual review. Any future automated repair workflow must independently verify the target, run tests, preserve a backup, require explicit operator approval, and support rollback before a change can be applied.
