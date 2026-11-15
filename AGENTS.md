# Instructions for coding agents

Before making any changes, read docs/PROJECT_CONTEXT.md. It is the canonical product brief and hand-off note.

- Preserve NEXORA as a Minecraft-style platform with its own authoritative server core, separate Android client and standalone dedicated server.
- Java Edition is out of scope. Do not copy proprietary Minecraft code or assets.
- Single-player must embed the same NEXORA Core used by dedicated multiplayer servers.
- Do not use Luanti; the user explicitly rejected it. Use Bedrock as a gameplay/protocol reference and implement NEXORA independently.
- Do not claim Bedrock compatibility, a playable client, or successful builds without corresponding CI and real-client/device evidence.
- Inspect existing code before implementing; reuse and extend it rather than starting over.
- Keep project context, README and architecture/status docs updated with meaningful decisions and tested progress.
- The user primarily uses an Android phone; produce Android CI artifacts when a milestone is installable.
