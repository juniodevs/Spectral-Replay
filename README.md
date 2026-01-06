# Spectral Replay

**Spectral Replay** is a Minecraft Spigot plugin that adds an immersive, haunting element to your server by recording and replaying player deaths as "ghosts". When a player dies, their final moments are captured and can be replayed later as a spectral apparition at the location of their death.

## 🎥 Showcase

Check out an example of the mod in action:

[Watch Video](https://youtu.be/7CSVs1f2R8Av)

## 👻 Features

*   **Death Replays:** Automatically records the last few seconds before a player's death.
*   **PVP Replays:** Special handling for PVP deaths, showing both the victim and the killer in a spectral battle.
*   **Proximity System:** Ghosts appear when players get close to the death location, creating jump-scare or atmospheric moments.
*   **Replay Lifecycle:** Replays have a "max plays" limit, after which they fade away and stop triggering automatically.
*   **Visual Effects:**
    *   **Death:** Subtle `SCULK_SOUL` particles and transparent ghosts.
    *   **PVP:** Intense "Soul Vortex" particle effects and combat sounds.
*   **Sound Design:** Custom soundscapes including ethereal screams, heartbeats, and combat noises to enhance immersion.
*   **Citizens Integration:** Uses Citizens NPCs to create smooth, realistic player animations (movement, armor, items, sneaking, attacks).
*   **Optimized Performance:**
    *   Uses asynchronous processing for recording and database operations.
    *   Consolidated proximity checks to reduce server load.
    *   Global cooldown system prevents multiple replays from overriding each other or spamming the server.
*   **Configurable:** Control cooldowns, replay duration, particle intensity, armor visibility, and lifecycle.

## 📋 Requirements

*   **Java:** 21 or higher.
*   **Server:** Spigot/Paper 1.21+.
*   **Dependencies:**
    *   [Citizens](https://ci.citizensnpcs.co/job/Citizens2/) (Required for NPC handling).

## ⚙️ Configuration

The `config.yml` allows you to tweak the plugin's behavior. Recently updated with new defaults and limits:

```yaml
# Minimum/Maximum time between random replay attempts (in ticks)
# Default: 1-3 minutes
min-delay: 1200
max-delay: 3600

# Maximum number of times a specific replay can be triggered via proximity/random events
# before being disabled forever (preserves database space but stops showing old ghosts).
max-plays-per-replay: 5

# Maximum number of concurrent replays allowed
max-concurrent-replays: 2

# Armor Visibility Settings
# Toggle armor rendering for different replay types
armor-pvp: true    # Show armor in PVP replays
armor-death: false # Hide armor in standard death replays (ghostly effect)

# Proximity Replay (Jumpscare) Settings
proximity-replay:
  enabled: true
  radius: 5        # Detection radius in blocks
  cooldown: 600    # Cooldown in seconds before a replay can trigger again
```

## 🎮 Commands & Permissions

All commands require the permission `spectralreplay.admin`.

*   `/spectral play <id>` - Force play a specific replay by ID.
*   `/spectral list` - List recent replays.
*   `/spectral place <id>` - Permanently place a replay loop at your current location.
*   `/spectral list-placed` - List all permanently placed replays.
*   `/spectral remove <id>` - Remove a placed replay.
*   `/spectral delete <id>` - Delete a replay from the database.
*   `/spectral reset-cooldowns` - Reset proximity cooldowns AND the global replay timer.

## 🛠️ Building from Source

1.  Clone the repository.
2.  Ensure you have Maven installed.
3.  Run `mvn clean package`.
4.  The compiled `.jar` file will be in the `target/` directory.

## 📝 Notes

*   **PVP Replays:** Trigger at any time of day.
*   **Death Replays:** By default, standard death replays only trigger during the night (Minecraft time 13000-23000) for extra spookiness.
*   **Database:** Uses SQLite by default. The database now tracks how many times each replay has been shown to enforce the `max-plays-per-replay` limit.

---
