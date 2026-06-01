package dev.sirdexal.events.lava;

import dev.sirdexal.events.EventsLog;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

import java.util.*;

/**
 * Central game state machine for Lava Rising.
 *
 * <p>States mirror the old datapack's {@code period internal} scoreboard:
 * IDLE(-1) → STARTER(0) → GRACE(1) → RISING(2) → VICTORY(3).</p>
 */
public class LavaGame {

    public enum State {
        IDLE,      // period -1: pre-game lobby
        STARTER,   // period  0: PvP disabled, resistance
        GRACE,     // period  1: PvP enabled, gathering
        RISING,    // period  2: LAVA IS RISING!
        VICTORY    // period  3: game over
    }

    private final LavaConfig config;
    private MinecraftServer server;

    private State state = State.IDLE;
    private int tickCounter = 0;
    private int elapsedSeconds = 0;

    private final LavaRiser riser;
    private final LavaTeams teams;
    private final LavaExtras extras;

    private ServerBossBar bossBar;

    // Death / win tracking
    private int aliveCount = 0;
    private final Set<UUID> deadPlayers = new HashSet<>();
    private UUID winnerUuid = null;

    // Late-join tracking (prevents re-applying effects on every tick)
    private final Map<UUID, State> lastJoinState = new HashMap<>();

    public LavaGame(LavaConfig config) {
        this.config = config;
        this.riser  = new LavaRiser(config);
        this.teams  = new LavaTeams();
        this.extras = new LavaExtras(config);
    }

    public void attachServer(MinecraftServer server) {
        this.server = server;
        bossBar = new ServerBossBar(
                Text.literal("The games will begin shortly!"),
                BossBar.Color.WHITE, BossBar.Style.PROGRESS);
        teams.ensureTeams(server);
        EventsLog.info("LavaGame attached. State={}", state);
    }

    public State getState() { return state; }
    public LavaTeams getTeams() { return teams; }

    // ───────────────────────────────────────────────────────────────────────────
    //  Tick loop
    // ───────────────────────────────────────────────────────────────────────────

    public void onTick(MinecraftServer server) {
        if (this.server == null) attachServer(server);
        if (state == State.IDLE) return;

        // Ensure boss bar visible
        for (ServerPlayerEntity p : online()) {
            if (!bossBar.getPlayers().contains(p)) bossBar.addPlayer(p);
        }

        // Extras (cut clean, speed UHC)
        if (state == State.GRACE || state == State.RISING) {
            extras.onTick(server);
        }

        // Performance: falling block cleanup
        if (state == State.RISING) {
            riser.performanceCleanup(server);
        }

        // ── Second gate (20 ticks = 1 second) ──
        tickCounter++;
        if (tickCounter >= 20) {
            tickCounter = 0;
            elapsedSeconds++;
            onSecond();
        }

        // Riser tick (only in RISING)
        if (state == State.RISING) {
            riser.onTick(server);
        }
    }

    private void onSecond() {
        switch (state) {
            case STARTER -> starterSecond();
            case GRACE   -> graceSecond();
            case RISING  -> risingSecond();
            default -> {}
        }
        updateBossBar();
    }

    // ── Period seconds ──

    private void starterSecond() {
        // Keep resistance on all survival players
        for (ServerPlayerEntity p : online()) {
            if (!p.isSpectator()) {
                p.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.RESISTANCE, 40, 255, true, false, false));
            }
        }

        int left = config.starterPeriod - elapsedSeconds;
        if (left == 30 || left == 15 || left == 10 || (left >= 1 && left <= 5)) {
            broadcastTitle(
                    Text.literal(String.valueOf(left)).formatted(Formatting.YELLOW, Formatting.BOLD),
                    Text.literal("PvP enabled soon").formatted(Formatting.WHITE), 0, 22, 3);
        }
        if (elapsedSeconds >= config.starterPeriod) transitionToGrace();
    }

    private void graceSecond() {
        int left = config.gracePeriod - elapsedSeconds;
        if (left == 300 || left == 120 || left == 60 || left == 30 ||
                left == 15 || left == 10 || (left >= 1 && left <= 5)) {
            broadcastTitle(
                    Text.literal(left + "s").formatted(Formatting.GOLD, Formatting.BOLD),
                    Text.literal("Lava begins rising soon!").formatted(Formatting.WHITE), 0, 22, 3);
        }
        if (elapsedSeconds >= config.gracePeriod) transitionToRising();
    }

    private void risingSecond() {
        checkWinCondition();
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Start / Stop
    // ───────────────────────────────────────────────────────────────────────────

    public void start() {
        if (server == null) { broadcast(err("Server not ready.")); return; }
        if (state != State.IDLE) { broadcast(err("A game is already in progress!")); return; }

        List<ServerPlayerEntity> players = online();
        if (players.size() < 2) { broadcast(err("Need at least 2 players to start.")); return; }

        if (config.teamsEnabled && !teams.validateTeams(server, config.teamCount)) {
            broadcast(err("Teams enabled but not all players are assigned to red/blue" +
                    (config.teamCount >= 3 ? "/green" : "") + "!"));
            return;
        }

        EventsLog.info("GAME START: {} players, teams={}", players.size(), config.teamsEnabled);

        // Reset state
        deadPlayers.clear();
        lastJoinState.clear();
        winnerUuid = null;
        elapsedSeconds = 0;
        tickCounter = 0;
        riser.reset();

        // Count alive
        aliveCount = 0;
        for (ServerPlayerEntity p : players) {
            p.changeGameMode(GameMode.SURVIVAL);
            p.clearStatusEffects();
            p.setHealth(p.getMaxHealth());
            p.getHungerManager().setFoodLevel(20);
            p.getHungerManager().setSaturationLevel(5f);
            aliveCount++;
        }
        if (config.teamsEnabled) teams.countPlayers(server);

        // World border
        setWorldBorder();

        // Announce
        broadcastTitle(
                Text.literal("LAVA RISING").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal("Gather resources before the lava begins to rise."),
                0, 80, 20);
        broadcast(ok("The game has started!"));
        playSound("entity.generic.explode");
        playSound("block.note_block.pling", 1f, 1f);

        state = State.STARTER;
        updateBossBar();
    }

    public void stop() {
        if (state == State.IDLE) { broadcast(info("No active game to stop.")); return; }
        EventsLog.info("GAME STOPPED (was {}).", state);
        state = State.IDLE;
        deadPlayers.clear();
        lastJoinState.clear();
        if (bossBar != null) bossBar.clearPlayers();
        for (ServerPlayerEntity p : online()) p.clearStatusEffects();
        broadcast(err("Game stopped."));
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  State transitions
    // ───────────────────────────────────────────────────────────────────────────

    private void transitionToGrace() {
        EventsLog.info("PERIOD: STARTER → GRACE at {}s", elapsedSeconds);
        state = State.GRACE;
        elapsedSeconds = 0;
        tickCounter = 0;

        for (ServerPlayerEntity p : online()) p.removeStatusEffect(StatusEffects.RESISTANCE);

        broadcast(warn("PvP has been enabled!"));
        playSound("block.note_block.pling", 1f, 0.8f);
        playSound("entity.arrow.hit_player", 1f, 0.2f);
        updateBossBar();
    }

    private void transitionToRising() {
        EventsLog.info("PERIOD: GRACE → RISING at {}s", elapsedSeconds);
        state = State.RISING;
        elapsedSeconds = 0;
        tickCounter = 0;

        // Recount alive
        aliveCount = 0;
        if (config.teamsEnabled) teams.countPlayers(server);
        for (ServerPlayerEntity p : online()) {
            if (p.interactionManager.getGameMode() == GameMode.SURVIVAL) aliveCount++;
        }

        broadcastTitle(
                Text.literal("LAVA RISING").formatted(Formatting.RED, Formatting.BOLD),
                Text.literal("The lava has begun rising!"), 0, 80, 20);
        broadcast(warn("The lava has begun rising!"));
        playSound("block.note_block.pling", 1f, 0.8f);
        playSound("entity.lightning_bolt.impact");
        updateBossBar();
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Death / Win
    // ───────────────────────────────────────────────────────────────────────────

    public void onPlayerDeath(ServerPlayerEntity player) {
        if (state != State.RISING) return;
        if (deadPlayers.contains(player.getUuid())) return;

        deadPlayers.add(player.getUuid());
        aliveCount--;
        if (config.teamsEnabled) teams.onPlayerDeath(player);

        EventsLog.info("ELIMINATED: {} (alive={})", player.getName().getString(), aliveCount);

        broadcast(Text.literal("[Events] ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal("☠ ").formatted(Formatting.RED))
                .append(Text.literal(player.getName().getString()).formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal(" has been eliminated!").formatted(Formatting.DARK_RED)));
        playSound("entity.lightning_bolt.thunder");
        checkWinCondition();
    }

    public void onPlayerRespawn(ServerPlayerEntity player) {
        if (deadPlayers.contains(player.getUuid())) {
            player.changeGameMode(GameMode.SPECTATOR);
        }
    }

    public void onPlayerJoin(ServerPlayerEntity player) {
        if (state == State.IDLE) return;
        if (bossBar != null) bossBar.addPlayer(player);

        State prev = lastJoinState.get(player.getUuid());
        if (prev == state) return;
        lastJoinState.put(player.getUuid(), state);

        if (deadPlayers.contains(player.getUuid())) {
            player.changeGameMode(GameMode.SPECTATOR);
            return;
        }

        switch (state) {
            case STARTER -> {
                player.changeGameMode(GameMode.SURVIVAL);
                player.addStatusEffect(new StatusEffectInstance(
                        StatusEffects.RESISTANCE, 9999, 255, true, false, false));
            }
            case GRACE, RISING -> {
                player.changeGameMode(GameMode.SURVIVAL);
                player.clearStatusEffects();
            }
            case VICTORY -> {
                if (!player.getUuid().equals(winnerUuid))
                    player.changeGameMode(GameMode.SPECTATOR);
            }
        }
    }

    private void checkWinCondition() {
        if (state != State.RISING) return;

        if (config.teamsEnabled) {
            String winner = teams.checkTeamWin(config.teamCount);
            if (winner != null) triggerVictoryTeam(winner);
        } else {
            if (aliveCount <= 1) {
                ServerPlayerEntity winner = null;
                for (ServerPlayerEntity p : online()) {
                    if (p.interactionManager.getGameMode() == GameMode.SURVIVAL
                            && !deadPlayers.contains(p.getUuid())) {
                        winner = p; break;
                    }
                }
                if (winner != null) triggerVictorySolo(winner);
                else if (aliveCount <= 0) triggerDraw();
            }
        }
    }

    private void triggerVictorySolo(ServerPlayerEntity winner) {
        EventsLog.info("GAME OVER: {} wins!", winner.getName().getString());
        state = State.VICTORY;
        winnerUuid = winner.getUuid();

        broadcastTitle(
                Text.literal("GAME OVER!").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal(winner.getName().getString() + " has won!").formatted(Formatting.YELLOW),
                0, 80, 20);
        broadcast(Text.literal("[Events] ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal("✔ ").formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal(winner.getName().getString()).formatted(Formatting.GOLD))
                .append(Text.literal(" has won!").formatted(Formatting.YELLOW)));
        playSound("ui.toast.challenge_complete");

        winner.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 9999, 255, true, false, false));
        winner.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 9999, 0, true, false, false));
        for (ServerPlayerEntity p : online()) {
            if (!p.getUuid().equals(winnerUuid)) p.changeGameMode(GameMode.SPECTATOR);
        }
        updateBossBar();
    }

    private void triggerVictoryTeam(String teamName) {
        EventsLog.info("GAME OVER: Team {} wins!", teamName);
        state = State.VICTORY;

        Formatting color = switch (teamName) {
            case "red"   -> Formatting.RED;
            case "blue"  -> Formatting.BLUE;
            case "green" -> Formatting.GREEN;
            default      -> Formatting.WHITE;
        };
        broadcastTitle(
                Text.literal("GAME OVER!").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal("Team " + teamName.toUpperCase() + " wins!").formatted(color, Formatting.BOLD),
                0, 80, 20);
        broadcast(Text.literal("[Events] ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal("✔ ").formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal("Team " + teamName.toUpperCase()).formatted(color, Formatting.BOLD))
                .append(Text.literal(" has won!").formatted(Formatting.YELLOW)));
        playSound("ui.toast.challenge_complete");

        for (ServerPlayerEntity p : online()) {
            if (teams.isOnTeam(p, teamName)) {
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 9999, 255, true, false, false));
                p.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 9999, 0, true, false, false));
            } else {
                p.changeGameMode(GameMode.SPECTATOR);
            }
        }
        updateBossBar();
    }

    private void triggerDraw() {
        EventsLog.info("GAME OVER: Draw — no survivors!");
        state = State.VICTORY;
        broadcastTitle(
                Text.literal("GAME OVER!").formatted(Formatting.GOLD, Formatting.BOLD),
                Text.literal("No survivors!").formatted(Formatting.RED), 0, 80, 20);
        updateBossBar();
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Bossbar
    // ───────────────────────────────────────────────────────────────────────────

    private void updateBossBar() {
        if (bossBar == null) return;
        switch (state) {
            case IDLE -> {
                bossBar.setName(Text.literal("The games will begin shortly!"));
                bossBar.setColor(BossBar.Color.WHITE);
                bossBar.setPercent(1f);
            }
            case STARTER -> {
                int left = Math.max(0, config.starterPeriod - elapsedSeconds);
                bossBar.setName(Text.literal("Starter period  ").formatted(Formatting.YELLOW)
                        .append(Text.literal(String.valueOf(left)).formatted(Formatting.YELLOW, Formatting.BOLD))
                        .append(Text.literal(" seconds left").formatted(Formatting.WHITE)));
                bossBar.setColor(BossBar.Color.YELLOW);
                bossBar.setPercent(clamp((float) elapsedSeconds / config.starterPeriod));
            }
            case GRACE -> {
                int left = Math.max(0, config.gracePeriod - elapsedSeconds);
                bossBar.setName(Text.literal("Grace period  ").formatted(Formatting.GOLD)
                        .append(Text.literal(String.valueOf(left)).formatted(Formatting.GOLD, Formatting.BOLD))
                        .append(Text.literal(" seconds left").formatted(Formatting.WHITE)));
                bossBar.setColor(BossBar.Color.YELLOW);
                bossBar.setPercent(clamp((float) elapsedSeconds / config.gracePeriod));
            }
            case RISING -> {
                bossBar.setName(Text.literal("LAVA RISING  ").formatted(Formatting.RED, Formatting.BOLD)
                        .append(Text.literal("Currently at Y: ").formatted(Formatting.WHITE))
                        .append(Text.literal(String.valueOf(riser.getLavaLevel())).formatted(Formatting.RED, Formatting.BOLD)));
                bossBar.setColor(BossBar.Color.RED);
                bossBar.setPercent(clamp((float) (riser.getLavaLevel() + 64) / (config.riseHeightLimit + 64)));
            }
            case VICTORY -> {
                bossBar.setName(Text.literal("Game over!"));
                bossBar.setColor(BossBar.Color.WHITE);
                bossBar.setPercent(1f);
            }
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Setup menu (Chest UI handled by LavaSetupGui)
    // ───────────────────────────────────────────────────────────────────────────

    public void showSetup(ServerCommandSource source) {
        ServerPlayerEntity p = source.getPlayer();
        if (p == null) { source.sendFeedback(() -> Text.literal("Must be run by a player."), false); return; }
        
        // Open the Chest GUI
        p.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, inv, player) -> new LavaSetupGui(syncId, inv, config, this),
                Text.literal("Lava Rising Setup").formatted(Formatting.RED, Formatting.BOLD)
        ));
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  World border
    // ───────────────────────────────────────────────────────────────────────────

    private void setWorldBorder() {
        if (server == null) return;
        try {
            net.minecraft.world.border.WorldBorder border = server.getOverworld().getWorldBorder();
            border.setCenter(0.0, 0.0);
            border.setSize(500.0); // 500x500 total, meaning [-250, 250]
            EventsLog.info("World border set to 500x500.");
        } catch (Exception e) {
            EventsLog.error("Failed to set world border", e);
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    //  Utilities
    // ───────────────────────────────────────────────────────────────────────────

    private List<ServerPlayerEntity> online() {
        return server == null ? List.of() : server.getPlayerManager().getPlayerList();
    }

    private void broadcast(Text msg) {
        for (ServerPlayerEntity p : online()) p.sendMessage(msg, false);
    }

    private void broadcastTitle(Text title, Text subtitle, int fadeIn, int stay, int fadeOut) {
        for (ServerPlayerEntity p : online()) {
            p.networkHandler.sendPacket(new TitleFadeS2CPacket(fadeIn, stay, fadeOut));
            p.networkHandler.sendPacket(new TitleS2CPacket(title));
            if (subtitle != null) p.networkHandler.sendPacket(new SubtitleS2CPacket(subtitle));
        }
    }

    /** Play a sound to all players at their own position (vanilla playsound command). */
    private void playSound(String sound) { playSound(sound, 1f, 1f); }

    private void playSound(String sound, float vol, float pitch) {
        if (!config.sfx) return;
        try {
            server.getCommandManager().getDispatcher().execute(
                    "execute as @a at @s run playsound minecraft:" + sound + " player @s ~ ~ ~ " + vol + " " + pitch,
                    server.getCommandSource());
        } catch (Exception ignored) {}
    }

    // Chat message helpers
    private Text ok(String msg) {
        return Text.literal("[Events] ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal("✔ ").formatted(Formatting.GREEN, Formatting.BOLD))
                .append(Text.literal(msg).formatted(Formatting.YELLOW));
    }
    private Text warn(String msg) {
        return Text.literal("[Events] ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal("! ").formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal(msg).formatted(Formatting.YELLOW));
    }
    private Text err(String msg) {
        return Text.literal("[Events] ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal("✘ ").formatted(Formatting.RED, Formatting.BOLD))
                .append(Text.literal(msg).formatted(Formatting.RED));
    }
    private Text info(String msg) {
        return Text.literal("[Events] ").formatted(Formatting.DARK_GRAY)
                .append(Text.literal(msg).formatted(Formatting.GRAY));
    }

    private static float clamp(float v) { return Math.min(1f, Math.max(0f, v)); }
}
