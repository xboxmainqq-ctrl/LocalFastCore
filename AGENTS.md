# Instructions for coding agents

Before making changes, read `docs/PROJECT_CONTEXT.md`, `docs/CORE_DEVELOPMENT_LOG.md` and `docs/NEXORA_AI_SYSTEMS_ROADMAP.md`.

## Current product direction — overrides historical repository documents

- NEXORA is a standalone, general-purpose block-world Core and dedicated server.
- **Do not build or prioritize an Android client.**
- **Do not build a separate RPG server/product or expand RPG gameplay.**
- Keep three AI capabilities in scope: native Mob AI, optional Core AI Assistant, optional Moderation AI Assistant.
- AI mods are out of scope.
- Historical Android, RPG and Bedrock experiments may remain in the repository, but do not treat them as active product requirements or delete useful code without a specific migration reason.
- Keep the dedicated server independently runnable. Server-side networking/protocol work is in scope only where it serves the standalone server.
- Do not copy proprietary Minecraft or Nukkit/PowerNukkitX source, assets or implementation. Public documentation and observable behavior may be used as clean-room references.
- Inspect existing code before implementing; extend it rather than re-scaffolding.
- Keep Core operational when optional AI modules/providers are disabled or unavailable.
- AI tools must be permission-checked and audited. Mutating actions require explicit operator approval. Moderation recommendations require human review by default.
- Do not claim a build/test passed without a successful CI run or actual local test evidence.
- Update the project context, Core log, AI roadmap and README after meaningful changes. Record implementation status separately from plans.
