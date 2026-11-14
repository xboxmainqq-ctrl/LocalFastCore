# NEXORA legacy compatibility matrix

This branch is a development branch. It does not replace the live server or alter existing plugin artifacts.

| Component | Current repository version | Runtime dependency | Compatibility handling |
| --- | --- | --- | --- |
| Nukkit-MOT | 1.26.60-R1 | Java 17 | Retained as the server and legacy plugin loader |
| LocalFastCore | 0.74.0 | Nukkit-MOT 1.26.60-R1 | Kept unchanged in this stage |
| ClassAbilities | 0.13.0 | Nukkit-MOT 1.26.60-R1; plugin dependency `LocalFastCore` | Kept unchanged; must continue to find the plugin named `LocalFastCore` |
| NexoraCore | 0.1.0-SNAPSHOT | Nukkit-MOT 1.26.60-R1 | New, separate foundation plugin; does not replace legacy APIs |

## Non-breaking migration rules

1. Do not rename the plugin ID `LocalFastCore` while ClassAbilities declares `depend: [LocalFastCore]`.
2. Do not change existing command names, permission nodes, public Java package names, or saved-data paths as part of the initial NexoraCore introduction.
3. New APIs are additive. Legacy plugin loading remains the responsibility of Nukkit-MOT.
4. Before any live deployment, test the exact server JAR and every installed plugin on a copy of the world and plugin data.
5. A green compile is not proof of runtime compatibility. Test plugin enable order, command registration, event handling, player join/quit, GUI responses, and persistence.

## Current limitation

NexoraCore 0.1.0-SNAPSHOT is a plugin-level foundation, not a forked server distribution. A true NEXORA server fork is a later milestone after the compatibility layer and integration tests are stable.
