# SirDexal Events 🌋

A server-side ecosystem mod for Minecraft **1.21.11**, running on Fabric. 
This mod acts as the foundation for multiple automated server-side events, currently featuring **Lava Rising**.

## 🚀 Features

### Lava Rising
A fully automated, zero-admin game mode that replaces the classic datapack approach for significantly improved performance and stability.

- **Chest GUI Setup:** Run `/events lava setup` to configure the game with an interactive chest UI. Toggle periods, adjust lava tick speed, configure Speed UHC and more.
- **Dynamic Teams:** Use `/events lava teams join <red|blue|green>` or automatically balance players with `/events lava teams auto`. Scoreboard integration is native.
- **Flawless World Border:** The game natively restricts the world border to 500x500 when starting, removing all scaling guesswork.
- **Built-in Performance Cleanup:** Seamlessly removes invalid entities and floating blocks in the border without impacting gameplay.
- **World Reset API Hook:** Use `/events world reset` to trigger a 10-second countdown that broadcasts a reset token to your external watcher bot (e.g. Pterodactyl wings watcher).

## 🛠 Commands

*Requires Operator Level 2+*

| Command | Action |
| --- | --- |
| `/events lava start` | Starts the game (runs through Starter → Grace → Rising → Victory). |
| `/events lava stop` | Forcefully aborts the active game mode. |
| `/events lava setup` | Opens the configuration Chest GUI. |
| `/events lava config <setting>` | Granular control over all game variables (grace, starter, rise_ticks, cut_clean, speed_uhc, sfx). |
| `/events lava teams join/leave/auto`| Manage player teams natively. |
| `/events world reset` | Starts a 10s countdown to output the external server restart token. |
| `/events world reset cancel` | Aborts a pending server wipe. |

## 📦 Installation

1. Download the latest `sirdexal-events-X.X.X.jar` from the releases tab (or build it yourself).
2. Place the mod into your `mods/` directory.
3. Ensure you have the **Fabric API** installed.
4. Restart your server. A `sirdexal-events` folder will be generated with your `lava-config.json` inside.

## 🔨 Building from Source

This project uses Gradle. To compile the `.jar` yourself:

```sh
./gradlew build
```
You can find the compiled jar in `build/libs/`.

---
*Created for SirDexal's Minecraft server ecosystem.*
