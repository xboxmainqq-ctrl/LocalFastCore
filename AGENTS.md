# Instructions for coding agents

Before making any changes, read docs/PROJECT_CONTEXT.md. It is the canonical product brief and hand-off note.

- Active server foundation is Nukkit-MOT, as explicitly confirmed by the user on 2026-11-16. Follow the latest Nukkit-MOT section in docs/PROJECT_CONTEXT.md; older standalone-runtime direction is superseded for active server work.
- Build and customize Nukkit-MOT in a NEXORA-owned repository/branch. Do not claim upstream changes or a fork exist unless created and verified.
- Preserve existing NEXORA standalone-runtime/client work; do not delete it as part of the server pivot.
- Java Edition is out of scope. Do not copy proprietary Minecraft code or assets.
- Do not claim Bedrock compatibility, a playable client, or successful builds without corresponding CI and real-client/device evidence.
- Inspect Nukkit-MOT APIs and current code before implementing; reuse and extend them rather than guessing interfaces.
- Keep project context, README and architecture/status docs updated with meaningful decisions and tested progress.
- Respect Nukkit-MOT's LGPL-3.0 license and preserve notices/source obligations for redistributed modifications.
- The user primarily uses an Android phone; provide CI artifacts and phone-friendly test instructions when a milestone is installable.
