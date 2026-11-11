# LocalFastCore 0.12.0

RPG progression plugin for Nukkit-MOT 1.26.60-R1 (Java 17).

## Features
- Player level and XP progression (maximum level: 100).
- Four stats: strength, health, agility and intelligence.
- Sidebar scoreboard and GUI stat allocation via `/lfmenu`.
- Daily login XP, mob-kill XP and daily tasks.
- Level-based quest GUI with 15 combat quests across five level brackets.
- Multiple accepted quests progress simultaneously.
- Quest progress, completion and claimed rewards persist in `players.yml`.
- UUID-based player profiles with additive profile schema versioning.

## Commands

| Command | Description |
| --- | --- |
| `/level` | Show level, XP and available points |
| `/stats` | Show current attributes |
| `/lfmenu` | Open attribute allocation menu |
| `/addstat <stat> [amount]` | Allocate stat points |
| `/quest` or `/quests` | Open the level-based quest menu |
| `/quest <mob>` | Start a legacy single-mob quest |
| `/quest claim` | Claim a legacy quest reward |
| `/daily` | View daily task |
| `/daily start` | Start the daily task |
| `/daily claim` | Claim the daily reward |
| `/balance` | Show your coin balance |
| `/pay <player> <amount>` | Transfer coins to an online player |
| `/sethome` | Save your current home location |
| `/home` | Teleport to your saved home |
| `/delhome` | Delete your saved home |
| `/tpa <player>` | Request to teleport to a player |
| `/tpaccept` | Accept the pending teleport request |
| `/tpdeny` | Deny the pending teleport request |
| `/lfadmin` | Operator-only control panel and commands |
| `/staff` | Staff moderation menu (admin/moderator) |

Valid stat names: `strength`, `health`, `agility`, `intelligence`.

## Quest GUI

Open `/quests` (or `/quest`) to browse five level brackets:
- Beginner: 1–10
- Adventurer: 11–30
- Veteran: 31–50
- Elite: 51–75
- Legend: 76–100

Each bracket contains three combat quests. A player can accept several quests at once; matching mob kills advance all accepted quests. The personal quests screen shows active and completed tasks. Completed rewards are claimed from each quest detail screen.

Players can browse any bracket, but can only accept quests while their level is inside that bracket. An accepted quest continues tracking even if the player later levels beyond its original bracket.

## Economy

Each UUID profile stores its own `balance` in `players.yml`. On first profile initialization, a missing balance is initialized from `economy.starting-balance`; an existing balance is never overwritten. Balances use whole-number amounts (up to the signed 64-bit limit).

- `/balance` — show your balance.
- `/pay <player> <amount>` — transfer a positive whole-number amount to another online player. Self-payments, offline targets, invalid amounts, insufficient funds and recipient balance overflow are rejected.

Configure `economy.starting-balance` and `economy.currency-name` in `config.yml`. Existing config files are not overwritten automatically; merge the `economy` section to customize the defaults. Transfers are written through the existing player-data save queue before success is reported.

## Player data saving

Player changes are marked dirty by the profile setters and batched into a single `players.yml` write by the main-thread autosave task. Each profile carries a `data_version` field so future upgrades can migrate data incrementally without resetting existing progress. The default interval is 60 seconds and can be changed with `autosave.interval-seconds` (5–3600 seconds). Pending changes are also flushed when a player quits and when the plugin disables. Nukkit `Config` access is kept on the server thread; failed saves are logged and remain pending for the next retry.

## Configuration

The plugin creates `plugins/LocalFastCore/config.yml` on first start. It controls scoreboard settings, progression rewards, daily task requirements, Intelligence XP bonuses and quest requirements/rewards.

Every level-based quest has its own settings under `quests.catalog`:

```yaml
quests:
  catalog:
    rookie_zombies:
      kills: 10
      reward: 100
```

The same `kills` and `reward` keys are available for all 15 quest IDs in the default config. Restart the server after changing configuration values.

## Upgrade notes

Version 0.10.0 keeps the existing player-data keys and adds quest state under each player's `quests` section in `players.yml`. No player-data reset is required. Existing `config.yml` files are not overwritten automatically; merge the new `quests.catalog` section into an existing config to customize the new quests. Back up the plugin folder before upgrading and test on a staging server before production.


## Player data identity and migration

Player profiles are keyed by UUID. On first join after upgrading, LocalFastCore copies the previous nickname-based profile into the UUID profile when one exists. The old section is retained as a backup. Make a backup of `plugins/LocalFastCore/` before upgrading and verify migrated player progress before removing any legacy sections.


## Admin commands

The `localfast.admin` permission grants access to `/lfadmin xp <player> <amount>` and selective progress resets:

- `/lfadmin reset <player> level` — resets level, XP and unspent points
- `/lfadmin reset <player> stats` — resets all four characteristics
- `/lfadmin reset <player> quests` — resets legacy, daily and level-based quest progress
- `/lfadmin reset <player> all` — resets all of the above

The target player must be online. Back up player data before using reset commands.


## Attribute formulas and safety limits (0.10.0)

Attribute effects are recalculated from saved values; they do not stack when the player rejoins, respawns, or reallocates points. The default formulas are:

- **Strength:** +1 base damage per 5 points, capped at +10 damage by default (hard ceiling +20).
- **Health:** +1 maximum HP per 5 points, capped at +20 HP by default (hard ceiling +40). Increasing max HP does not heal the player; current HP is preserved in absolute points, and only clamped if it exceeds the new maximum.
- **Agility:** +1% movement speed per 5 points, capped at +25% by default (hard ceiling +50%), still bounded by Nukkit-MOT's maximum movement speed.
- **Intelligence:** +1% XP per 5 points, capped at +50% by default (hard ceiling +100%). Legacy intelligence.xp-bonus-per and intelligence.xp-bonus-percent values remain fallback defaults for older config files.

New settings live under attributes.* in config.yml. Existing config files are not overwritten automatically; merge the attributes section from the shipped config to customize these formulas. The hard ceilings remain active even if a config value is set too high. progression.max-stat continues to limit saved attribute points independently.


## Spawn and RTP (0.10.0)

The plugin registers `/spawn`, `/setspawn` and `/rtp`. `/setspawn` requires the `localfast.admin` permission. Configure `spawn-world` with the exact world folder name (default: `spawn`). If that world is missing, `/spawn` now falls back to the server's default world instead of failing; stand at the desired location and run `/setspawn` (requires `localfast.admin`) to save the actual spawn world and coordinates. A separate lobby world must already exist under `worlds/`; this plugin does not bundle or generate a medieval map.

`main-world` selects the RTP destination world; an empty value uses the server default world. RTP searches up to 120 random locations in the configured annulus around the destination world's safe-spawn coordinates, using `rtp-min-radius` and `rtp-max-radius`. It only teleports when the ground is solid and the two blocks above it are non-solid. This makes the search work on maps whose playable area is not centered at X=0, Z=0. Configure a sensible radius for the actual size and border of your world. Test on a staging server before production.

## Release checks

The automated Maven build checks compilation and packaging only. Before production, test spawn-world loading, `/setspawn` persistence after restart, RTP at the world border and in different biomes, quest GUI navigation/claim retry, and profile migration on a staging copy. Back up `plugins/LocalFastCore/` and player data before upgrading. No command in this release automatically wipes player profiles; do not run `/lfadmin reset` unless an intentional administrative reset is required.


## 0.11.0 development notes

This development version keeps the existing UUID profile layout and adds an additive `data_version: 1` marker when a player profile is initialized. Existing level, XP, stats, daily tasks, and quest fields are not reset or rewritten. Integer and boolean profile setters now mark the data store dirty themselves, reducing the chance that a future feature forgets to schedule persistence. The existing main-thread autosave and quit/disable flush behavior remains in place.

No `players.yml` reset or destructive migration is part of this update. Back up `plugins/LocalFastCore/` before testing any development build. Automated CI validates compilation and packaging; in-game persistence still needs a server test.


## Homes and player teleport requests (0.11.0)

Each player can save one home with `/sethome`, return using `/home`, and remove it with `/delhome`. The home stores world folder name and coordinates inside the player's existing UUID profile in `players.yml`. If the saved world is unavailable, the home is retained and the player receives an error; it is not silently redirected to another world.

Use `/tpa <player>` to request teleportation to an online player. The target can use `/tpaccept` or `/tpdeny`; requests expire after 60 seconds. A player has one pending request slot at a time. Pending requests are in-memory only and are cleared when either player leaves or the server restarts. These commands are player-only and do not require admin permission.

Back up `plugins/LocalFastCore/` before testing on a live server. Test home persistence across restart and TPA accept/deny/timeout with two accounts before production use.


## Operator and staff system (0.12.0)

The operator identity is bound to a UUID in `operator.uuid`, not to a nickname. On first startup, the owner must join and the server console must run `lfadmin setoperator <online-player>`. This one-time bootstrap command is console-only. The operator can then assign staff by UUID using `/lfadmin staff add <player> admin|moderator`, remove them using `/lfadmin staff remove <player>`, and list staff using `/lfadmin staff list`.

`/lfadmin` is reserved for the configured operator. It includes staff assignment, online-player economy administration (`money give|take|set|reset`), and RPG XP/progress administration. `/staff` opens a GUI for staff members. Admins can ban, mute, kick, and remove bans/mutes; moderators can mute, kick, and remove mutes. Command forms support custom reasons and durations: `/staff ban <player> <minutes|-1> <reason>`, `/staff mute <player> <minutes> <reason>`, `/staff kick <player> <reason>`, `/staff unban <player>`, and `/staff unmute <player>`. GUI defaults are a 24-hour ban, 30-minute mute, and a kick with a generic reason.

Staff roles and active punishments are stored separately in `staff.yml`; staff actions are appended to `staff.log`. Ban enforcement is checked on join and mute enforcement cancels chat. Staff target selection and punishment removal currently require the target to be online. The automated build validates compilation and packaging; moderation behavior must still be tested in-game on a staging server.
