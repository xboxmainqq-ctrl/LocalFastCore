# NEXORA Core evolution map

**Date:** 2026-11-16  
**Status:** design baseline; no Nukkit-MOT engine source has been modified by this document.

This document defines how NEXORA will evolve from a pinned Nukkit-MOT server into an independently owned game server core without destabilizing the first playable server.

## Guiding rule

Keep Nukkit-MOT as the compatibility and hosting foundation until a replacement component has:
1. a documented NEXORA-owned interface;
2. automated tests covering the required behavior;
3. a compatibility adapter or migration path;
4. a successful server build and runtime test.

Do not rewrite a subsystem merely to remove its upstream name. Replace it when NEXORA needs behavior or ownership that the upstream subsystem cannot safely provide.

## Observed upstream integration seams

The following classes were inspected at pinned upstream revision `3258c14baf71792c289519d623ef14e7cc02dd37`:

| Concern | Upstream seam | Initial NEXORA strategy |
|---|---|---|
| Server lifecycle and registries | `cn.nukkit.Server` | Start with a bootstrap/configuration adapter; keep upstream lifecycle intact |
| Player session and gameplay API | `cn.nukkit.Player` | Add NEXORA player profile/session services outside Player initially |
| Network and packet processing | `cn.nukkit.network.Network` | Preserve protocol implementation; add packet compatibility tests before any fork-level changes |
| World and chunk behavior | `cn.nukkit.level.Level` | Keep upstream storage/chunk format; introduce NEXORA world metadata and services separately |
| Entities and combat | `cn.nukkit.entity.Entity` | Build gameplay components using supported entity/event APIs first |
| Plugin lifecycle and permissions | `cn.nukkit.plugin.PluginManager` | Use plugins as the first deployment boundary; avoid hard forks for ordinary features |

These are integration seams, not promises that every internal method is a stable public API. Exact extension points must be confirmed against the imported source and tests.

## Target NEXORA-owned modules

The first modules should be isolated from Nukkit internals and communicate through small interfaces:

- **nexora-platform-api** — stable interfaces, identifiers, lifecycle contracts and module metadata.
- **nexora-identity** — player identity/profile mapping, session metadata and future account integration. Do not invent an authentication protocol in the baseline milestone.
- **nexora-permissions** — rank inheritance, permission evaluation and audit events; initially adapt to Nukkit permissions.
- **nexora-gameplay** — classes, stats, XP, abilities, cooldowns and combat rules.
- **nexora-world** — NEXORA world metadata, rulesets, regions and progression state; keep chunk persistence compatible at first.
- **nexora-content** — custom items, entities, NPCs, bosses and resource-pack manifests, introduced only after client compatibility tests.
- **nexora-observability** — structured logs, health/status reporting and diagnostics.

The repository's current Maven root still belongs to the preserved Standalone Runtime. Do not turn it into the Nukkit-MOT build or place Nukkit imports into it. The pinned baseline workflow currently builds upstream in an isolated CI workspace; it is a validation scaffold, not yet the NEXORA-owned source tree.

## Replacement sequence

1. **Baseline:** build and boot pinned Nukkit-MOT; verify McpeHost and retail Bedrock 1.26.60.
2. **Product shell:** NEXORA config, branding, version metadata, startup diagnostics and release packaging.
3. **Platform boundary:** create the NEXORA module API and lifecycle adapter, with tests.
4. **Gameplay ownership:** move ranks, staff, RPG classes/stats/abilities and economy into NEXORA modules.
5. **World/content ownership:** add NEXORA world rules, custom content and resource-pack pipeline with client tests.
6. **Selective engine replacement:** replace network, entity, world or persistence internals only where a measured requirement justifies it. Keep protocol compatibility tests and a rollback path.
7. **Independent core:** remove upstream runtime dependencies component by component only after NEXORA replacements pass parity and load tests.

## Non-goals for the first milestone

- No launcher or Android client work.
- No wholesale rewrite of Nukkit-MOT.
- No claim of full protocol independence or complete Bedrock 1.26.60 support from source inspection alone.
- No deletion of the Standalone Runtime.
- No changes to upstream source until it exists in a NEXORA-owned fork/import with LGPL notices preserved.

## Completion evidence

Every stage must record the source revision, build command, CI run, artifact identifier, test results and any known limitations. Host/client acceptance is a separate gate from successful compilation.
