# Portable Plugin API

This is a standalone Java 17 API module for plugins that must run first on Nukkit and later on NEXORA.

## Portability rule

- Gameplay rules, progression, permissions policy, cooldowns and persistence models belong in a platform-neutral module.
- This API is the only platform boundary used by that logic.
- A Nukkit adapter implements these interfaces and translates Nukkit events, players, worlds and scheduler tasks.
- A future NEXORA adapter implements the same interfaces against NEXORA runtime services.
- Never import `cn.nukkit.*` into this module or into gameplay/domain modules.

A plugin is not automatically binary-compatible with both servers: each server needs its own adapter artifact. The shared gameplay code should not need to be rewritten.

## Module layout for each future plugin

```
example-plugin/
  domain/       # pure Java gameplay logic and tests
  portable/     # depends on portable-plugin-api
  nukkit/       # Nukkit-specific adapter and plugin.yml
  nexora/       # future NEXORA adapter
```

## Build

Run `mvn -f plugins/portable-api/pom.xml verify`.

The API intentionally starts small. Add a platform capability only when a real plugin needs it; do not expose an entire server implementation through a generic wrapper.
