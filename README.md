# NEXORA

NEXORA is an original voxel sandbox game platform inspired by the freedom and gameplay loop of block-building games. It is not a Minecraft client or a renamed Minecraft server.

## Repository structure

- `client-android/` — first-party Android game client: app, rendering, input, UI, local worlds and client networking.
- `src/` — NEXORA-owned Java game/server core and world systems.
- `nexora-modules/platform-api/` — extension API for the NEXORA core.
- `server/nukkit-mot/` — pinned Nukkit-MOT upstream source as a Git submodule, kept only as a reference for studying server architecture and Bedrock networking. It is not the NEXORA core.

## Development priority

1. Build a playable NEXORA Android voxel game: local world, camera/movement, block breaking/placing and save/load.
2. Evolve the independent NEXORA Core alongside the client, keeping rendering and Android APIs out of the core.
3. Study Nukkit-MOT when server/network implementation details are useful. Do not make NEXORA a thin Nukkit rebrand.
4. Add multiplayer and RPG systems after the basic local game loop is playable.

## Nukkit-MOT reference

The submodule is pinned to upstream revision `3258c14baf71792c289519d623ef14e7cc02dd37`. Nukkit-MOT is licensed under LGPL-3.0; preserve its notices and comply with the license when building or distributing derivative source/binaries. The upstream repository remains the source of truth.

Initialize it after cloning:

```bash
git submodule update --init --recursive
```

## Build

- Android client: open `client-android/` in Android Studio.
- NEXORA Core: use the root Maven project (`mvn test` / `mvn verify`).
- Nukkit-MOT is not included in the NEXORA Maven build; it is a separate reference submodule.
