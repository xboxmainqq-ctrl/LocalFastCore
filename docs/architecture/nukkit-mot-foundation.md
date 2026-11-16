# NEXORA server foundation: Nukkit-MOT

**Decision date:** 2026-11-16  
**Status:** architecture decision recorded; NEXORA fork/build integration not yet implemented.

## Decision

NEXORA's active server will be based on Nukkit-MOT. We will not continue building a separate server runtime from scratch as the primary hosting server. Nukkit-MOT supplies the Bedrock server/network foundation; NEXORA will own the fork, product identity, configuration, gameplay modules and future core patches.

## Upstream baseline

- Repository: https://github.com/MemoriesOfTime/Nukkit-MOT
- License reported by GitHub: LGPL-3.0
- Baseline inspected on 2026-11-16: `3258c14baf71792c289519d623ef14e7cc02dd37`
- Upstream currently supports multiple Bedrock protocol versions. This does not by itself certify NEXORA or a specific host/client combination.

The connected GitHub account has read-only access to upstream. The NEXORA-owned repository must contain the fork or imported source before we can modify the engine. No upstream changes or NEXORA fork are claimed by this decision record.

## Migration strategy

1. **Pin and import upstream.** Create a NEXORA-owned fork or vendor the upstream source at the recorded commit, preserving history and license notices.
2. **Reproduce the baseline.** Add CI that builds the unmodified pinned source and publishes a test server artifact.
3. **Prove hosting compatibility.** Install that artifact on McpeHost and test retail Bedrock 1.26.60. Keep the known-good Nukkit-MOT configuration available as rollback.
4. **NEXORA identity/config.** Add NEXORA server name, defaults, config layout, startup banner and version metadata without breaking upstream behavior.
5. **Extension layer.** Implement NEXORA ID, ranks/permissions, staff tools, RPG classes/stats/abilities, economy, mobs/bosses and events as isolated server modules/plugins where the API permits.
6. **Core patches only when needed.** If a feature cannot be implemented safely through the plugin API, make a small documented Nukkit-MOT core patch with tests.
7. **Resource packs.** Reimplement pack discovery, validation and Bedrock resource-pack negotiation using Nukkit-MOT's actual APIs. The standalone-runtime resource-pack loader is not automatically compatible with Nukkit-MOT.
8. **Release discipline.** Pin upstream revisions, maintain a changelog, preserve license notices, test upgrades, and never advertise Bedrock compatibility until real-client/host acceptance tests pass.

## What is not being done yet

- No Nukkit-MOT fork/import has been created in this repository yet.
- No Nukkit-MOT source files have been modified.
- The previous NEXORA Standalone Runtime remains preserved as a separate experiment; it is not the active server foundation.
- The prior standalone resource-pack pipeline has not been ported.
- McpeHost deployment and retail-client verification have not been performed.

## First technical milestone

A NEXORA-owned, reproducibly built Nukkit-MOT server artifact that starts on McpeHost and accepts a retail Bedrock 1.26.60 client. Only after that baseline is verified should broad NEXORA gameplay customization proceed.
