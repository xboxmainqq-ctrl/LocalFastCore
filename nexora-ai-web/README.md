# NEXORA AI Web Dashboard

Static tablet-first dashboard prototype for NEXORA AI.

## Open locally
Open index.html in a modern browser. No package manager or external JavaScript dependencies are required.

## Included prototype features
- Navigation across all six planned game-creation studios.
- Local browser project/task list using localStorage.
- Seeded procedural height/biome preview in World Studio.
- Export of generated preview data as JSON.
- Biome configuration JSON export.
- Model descriptor JSON export (not a 3D mesh editor).
- Pixel texture canvas with PNG export.
- 16x16 structure layout editor with JSON export.
- Live Generation event list showing actual stages executed by the preview generator.

## Not implemented yet
- Live AI inference and provider connections.
- Android host synchronization or remote access.
- Full 3D mesh editor and GLB export.
- Production world/chunk format or compatibility with the NEXORA game engine.
- Production-grade save/version system; browser projects are local to this browser.

The dashboard is bundled into the Android host APK under app/src/main/assets/web.
