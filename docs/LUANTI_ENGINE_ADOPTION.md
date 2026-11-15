# Luanti engine adoption for NEXORA

Status: evaluation and integration foundation. The engine is **not yet wired into the NEXORA Android app**.

## Decision

Use the upstream [Luanti](https://github.com/luanti-org/luanti) voxel engine as the candidate native runtime for NEXORA instead of expanding a home-grown renderer into a complete engine. Pin the upstream source to commit `a10dc8dbff3b0af8295ae55df44f65b1d9ee92c7` (2026-11-09) so builds are reproducible and upgrades are deliberate.

Luanti already has an Android application build, an SDL-based native game activity, OpenGL ES rendering, touchscreen support, and a single-player mode backed by its own in-process server. The Android project is a source/NDK build, not a drop-in Maven library.

## Important compatibility boundary

Luanti is **not** a Minecraft Bedrock engine and does not speak the NEXORA/Bedrock-style packet protocol implemented by the current Java client/server code.

Adopting Luanti means:
- the in-game renderer, world simulation, local single-player server, and save format initially come from Luanti;
- NEXORA gameplay is implemented as a separate Luanti game package (Lua definitions and scripts), with NEXORA-owned names, blocks, items, recipes, biomes, mobs, UI and progression;
- multiplayer initially uses Luanti's networking protocol unless a separately designed NEXORA gateway/adapter is built;
- existing Java protocol codecs and the current Java server are retained during the proof of concept, but are not assumed to interoperate with Luanti.

Do not remove the current Android client, Java server, or `:world-core` module until a working Luanti-based NEXORA prototype has been built and tested.

## Android integration approach

1. Keep the upstream engine as a pinned Git submodule under `third_party/luanti`.
2. Build the upstream `android` Gradle project and `:native` NDK module in CI before changing the production app.
3. Produce a branded NEXORA test APK with a separate application ID first. Verify install, launch, touch controls, local world creation, save/reload, and offline play on a real Android phone.
4. Add a NEXORA game package and migrate NEXORA content into it. Do not ship the default Luanti game as if it were NEXORA.
5. Only after the native prototype works, decide how to merge the Luanti Android activity into the existing NEXORA launcher/world-selection UI.
6. Revisit multiplayer architecture explicitly: native Luanti protocol, a NEXORA gateway, or a separate server implementation. Do not claim Bedrock compatibility.

## What remains useful from the current NEXORA code

The existing Java app already contains a world list/store, a shared deterministic terrain generator, a GLES2 chunk renderer, first-person camera support, movement/jump/collision code, block targeting, and custom network packet codecs. These are useful references and may be reused selectively. They are not automatically compatible with Luanti's world format or runtime.

The current `:world-core` can remain as a protocol-independent source of NEXORA rules/data during migration, but it must not become a second competing terrain/world authority. A clear migration boundary is required before connecting it to Lua/C++.

## License and source obligations

Luanti's engine is licensed under **LGPL-2.1-or-later**. Keep upstream copyright and license notices intact. Before distributing a NEXORA APK containing Luanti:
- include the applicable LGPL license text and third-party notices in the app;
- identify Luanti and describe significant engine modifications;
- provide users with access to the corresponding source for the exact engine version and all modifications shipped in the APK;
- ensure users can replace/modify the LGPL engine component as required by the license;
- audit bundled dependencies and game assets separately; their licenses may differ.

The repository submodule pin identifies the exact upstream source revision. It is not, by itself, a substitute for publishing the corresponding source with a distributed binary.

## First acceptance gate

The first integration milestone is deliberately small: CI builds an unmodified Luanti Android APK from the pinned source, then a NEXORA-branded test build launches on Android and can create, save, close, and reopen an offline world. No existing NEXORA networking or gameplay feature is declared migrated until it is implemented and tested on that runtime.
