# LocalFastCore 0.9.0

RPG progression plugin for Nukkit-MOT 1.26.60-R1 (Java 17).

## Features
- Player level and XP progression (maximum level: 100).
- Four stats: strength (bonus melee damage), health (maximum health), agility (movement speed), intelligence (bonus XP).
- Sidebar scoreboard with configurable refresh interval.
- GUI stat allocation via `/lfmenu`.
- Daily login XP, mob-kill XP, daily tasks and mob-type quests.
- Player progress stored in `plugins/LocalFastCore/players.yml`.

## Commands
| Command | Description |
| --- | --- |
| `/level` | Show level, XP and available points |
| `/stats` | Show current attributes |
| `/lfmenu` | Open the attribute allocation menu |
| `/addstat <stat> [amount]` | Allocate points by command |
| `/quest` | View available or active quests |
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
- Level-up title and subtitle.

Restart the server after changing configuration values.

## Upgrade notes
Version 0.9.0 keeps the existing player-data keys from 0.8.0. Back up the plugin folder before upgrading. Test the new build on a staging server before replacing a production JAR.
