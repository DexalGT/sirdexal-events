package dev.sirdexal.events.lava;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.sirdexal.events.EventsLog;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON-persisted configuration for the Lava Rising game mode.
 * Stored at {@code <server>/sirdexal-events/lava-config.json}.
 */
public class LavaConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static transient Path configPath;

    // ─── Periods (seconds) ───
    public int starterPeriod = 60;       // 1 minute no-PvP starter
    public int gracePeriod = 600;        // 10 minutes gathering

    // ─── Lava ───
    public int riseTicks = 80;           // ~4 seconds per 1-block rise
    public int riseHeightLimit = 316;    // Max Y the lava can reach

    // ─── Teams ───
    public boolean teamsEnabled = false;
    public int teamCount = 2;            // 2 or 3

    // ─── Extras ───
    public boolean cutClean = true;      // Auto-smelt ores & food
    public boolean speedUhc = true;      // Give Haste I for fast mining
    public boolean sfx = true;           // Sound effects

    // ─── Performance ───
    public boolean killNearbyFallingBlocks = true;
    public int killNearbyDistance = 2;
    public boolean killAllFallingBlocks = false;
    public boolean clearIllegalBlocks = true;

    public static LavaConfig load() {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("sirdexal-events");
            Files.createDirectories(dir);
            configPath = dir.resolve("lava-config.json");

            if (Files.exists(configPath)) {
                try (Reader reader = Files.newBufferedReader(configPath)) {
                    LavaConfig cfg = GSON.fromJson(reader, LavaConfig.class);
                    if (cfg != null) {
                        cfg.save(); // re-write to pick up any new fields
                        return cfg;
                    }
                }
            }
        } catch (Exception e) {
            EventsLog.error("Failed to load lava config", e);
        }

        LavaConfig cfg = new LavaConfig();
        cfg.save();
        return cfg;
    }

    public void save() {
        if (configPath == null) return;
        try (Writer writer = Files.newBufferedWriter(configPath)) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            EventsLog.error("Failed to save lava config", e);
        }
    }
}
