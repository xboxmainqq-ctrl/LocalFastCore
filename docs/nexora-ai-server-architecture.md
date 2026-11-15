# NEXORA AI — Platform Architecture

Status: approved architecture and initial Android host prototype. This document is a plan; modules are not represented as implemented until code and tests exist.

## Product goals

NEXORA AI is a tablet-first AI development environment. Initial target device: Xiaomi Pad 8, 8 GB RAM, 128 GB storage. No desktop computer is assumed. The design prioritizes zero mandatory spending: no paid model API, paid subscription, or paid server is required by default. Free cloud services can have quotas and can change their terms; local open-weight models still require storage, RAM, compute, and compatible licenses.

## High-level components

- Android Host: foreground service, local API, device status, secure token storage.
- Web Dashboard: chat, projects, file browser, task progress, settings.
- AI Engine: model gateway, provider adapters, free-only model registry, task router, coding agent.
- Project System: workspace, version history, checkpoints, backups, import/export.
- Build System: isolated Gradle/JDK workers, test runner, APK artifact collection.
- Game Creation Studio: six specialist editors connected to a common asset/project model.
- Preview Engine: browser-based 3D and world visualization.
- Export System: validated asset bundles for the NEXORA game engine and Android client.

## Free-only model policy

The Model Router may choose only:
1. Local models that the user explicitly installs and whose licenses permit the intended use.
2. Cloud endpoints that are verified to have a genuinely free tier at the time of use.

No paid API fallback. No automatic billing, paid upgrade, or top-up. If free quota is exhausted or a provider changes its terms, the router pauses and reports that no free route is currently available. It must never silently send a paid request.

Model Registry records provider, model ID, capabilities, context limit, modalities, license, endpoint status, quota notes, last verification time, and whether the route is local or hosted. Router scoring considers task type, context size, code quality, image support, latency, current availability, license, and zero-cost eligibility.

API credentials remain server-side, encrypted at rest, and never appear in browser JavaScript or logs. Local inference is optional; it must not run continuously on the tablet.

## Game Creation Studio

### World Studio
Seeded world creation, terrain controls, world preview, layer visibility, chunk/region streaming, export.

### Model Studio
Voxel and general 3D asset authoring, transform controls, materials, animation preview, GLB/glTF export where compatible.

### Biome Studio
Biome parameters including elevation, temperature, humidity, vegetation, surface materials, transitions, and placement rules.

### Texture Studio
Pixel-art and material editing, texture atlases, PNG export, palette management, and asset validation.

### Structure Studio
Reusable structure templates, placement rules, procedural variants, dungeons, villages, and structure previews.

### Live Generation
A streamed event timeline for generation stages, progress, seed, current region/chunk, warnings, errors, pause/resume, and deterministic replay. It visualizes actual generator events; it must not show fabricated progress.

## Shared asset and world formats

- Models: glTF/GLB where appropriate.
- Textures: PNG.
- Biome definitions and metadata: versioned JSON schemas.
- Structures: versioned NEXORA Structure format.
- Worlds: versioned NEXORA World format with deterministic seed and generator version.
- Exports include a manifest, format version, checksums, and compatibility metadata.

## Device resource strategy

- Stream world chunks; do not load an entire large world into memory.
- Generate in bounded batches and checkpoint between stages.
- Limit concurrent workers and pause when Android reports memory pressure or thermal constraints.
- Keep generated previews low-detail by default; offer higher detail on demand.
- Allow cache cleanup without deleting source assets or project history.
- Use remote free CI only within current free quotas; local builds remain the fallback when feasible.

## Security and networking

The initial Android prototype binds only to 127.0.0.1. Remote access is intentionally absent. Before adding remote pairing, require device pairing, short-lived credentials, TLS or a private-network tunnel, explicit user approval, rate limits, and audit logs. Never expose the development API directly to the public internet.

## Delivery phases

1. Android Host prototype and CI APK.
2. Web Dashboard and local project API.
3. Free-only Model Gateway and Router.
4. Coding agent with safe file operations, diffs, tests, and checkpoints.
5. Build workers and APK artifact workflow.
6. Game Creation Studio foundation and shared asset schema.
7. World/biome generation preview and Live Generation events.
8. Model, texture, and structure editors.
9. Export/import integration with the NEXORA game engine and client.

## Non-goals for the first prototype

The first Android Host is not a full AI assistant, does not run models, does not compile the game, does not provide remote access, and does not implement the six studios. Those are subsequent milestones.
