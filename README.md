# LocalFastCore 0.9.3

RPG progression plugin for Nukkit-MOT 1.26.60-R1 (Java 17).

## Features
- Player level and XP progression (maximum level: 100).
- Four stats: strength, health, agility and intelligence.
- Sidebar scoreboard and GUI stat allocation via `/lfmenu`.
- Daily login XP, mob-kill XP and daily tasks.
- Level-based quest GUI with 15 combat quests across five level brackets.
- Multiple accepted quests progress simultaneously.
- Quest progress, completion and claimed rewards persist in `players.yml`.

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
| `/lfadmin xp <player> <amount>` | Give XP (requires `localfast.admin`) |

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

## Player data saving

Player changes are marked dirty and batched into a single `players.yml` write by the main-thread autosave task. The default interval is 60 seconds and can be changed with `autosave.interval-seconds` (5–3600 seconds). Pending changes are also flushed when a player quits and when the plugin disables. Nukkit `Config` access is kept on the server thread; failed saves are logged and remain pending for the next retry.

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

Version 0.9.3 keeps the existing player-data keys and adds quest state under each player's `quests` section in `players.yml`. No player-data reset is required. Existing `config.yml` files are not overwritten automatically; merge the new `quests.catalog` section into an existing config to customize the new quests. Back up the plugin folder before upgrading and test on a staging server before production.


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
- **Health:** +1 maximum HP per 5 points, capped at +20 HP by default (hard ceiling +40). When max HP increases, current HP increases by the same amount; this preserves the amount of missing health.
- **Agility:** +1% movement speed per 5 points, capped at +25% by default (hard ceiling +50%), still bounded by Nukkit-MOT's maximum movement speed.
- **Intelligence:** +1% XP per 5 points, capped at +50% by default (hard ceiling +100%). Legacy intelligence.xp-bonus-per and intelligence.xp-bonus-percent values remain fallback defaults for older config files.

New settings live under attributes.* in config.yml. Existing config files are not overwritten automatically; merge the attributes section from the shipped config to customize these formulas. The hard ceilings remain active even if a config value is set too high. progression.max-stat continues to limit saved attribute points independently.
