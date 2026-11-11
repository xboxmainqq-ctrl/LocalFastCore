# LocalFastCore 0.9.3

RPG progression plugin for Nukkit-MOT 1.26.60-R1 (Java 17).

## Features
- Player level and XP progression (maximum level: 100).
- Four stats: strength (bonus melee damage), health (maximum health), agility (movement speed), intelligence (bonus XP).
- Sidebar scoreboard with configurable refresh interval.
- GUI stat allocation via `/lfmenu`.
- Daily login XP, mob-kill XP, daily tasks and level-based quest GUI with 15 quests across five level brackets.
- Player progress stored in `plugins/LocalFastCore/players.yml`.

## Commands
| Command | Description |
| --- | --- |
| `/level` | Show level, XP and available points |
| `/stats` | Show current attributes |
| `/lfmenu` | Open the attribute allocation menu |
| `/addstat <stat> [amount]` | Allocate points by command |
| `/quest` or `/quests` | Open the level-based quest GUI |
| `/quest <mob>` (zombie, skeleton or spider) | Start a mob-specific quest |
| `/quest claim` | Claim a completed quest |
| `/daily` | View daily task |
| `/daily start` | Start the daily task |
| `/daily claim` | Claim the daily reward |
| `/lfadmin xp <player> <amount>` | Give XP (requires `localfast.admin`) |

Valid stat names: `strength`, `health`, `agility`, `intelligence`.

## Configuration
The plugin creates `plugins/LocalFastCore/config.yml` on first start. It controls:
- Sidebar enablement, title, footer and refresh interval.
- Points per level, stat cap, mob-kill and login XP.
- Intelligence XP bonus scaling.
- Daily task kill requirement and XP reward.
- Quest kill requirement and individual XP rewards for any mob, zombies, skeletons and spiders.
- Level-up title and subtitle.

Restart the server after changing configuration values.

## Quest configuration

The `quests` section controls the shared kill requirement and each quest reward:
- `quests.kills-required`: number of matching mobs required.
- `quests.rewards.any`, `zombie`, `skeleton`, `spider`: XP reward for each quest type.

Daily task start messages now use the configured kill requirement. Quest menus, progress, completion checks and reward messages all read the same configuration values. Mob-kill XP messages also show the intelligence bonus percentage when it applies.

## Upgrade notes
Version 0.9.3 preserves the existing `players.yml` format; no player-data reset or migration is required. Existing installations receive the new quest defaults when the plugin creates a fresh config, but an existing `config.yml` is not overwritten. To use the new `quests` settings on an existing server, merge the section above into the existing config. Back up the plugin folder before upgrading and test the build on a staging server before replacing a production JAR.
