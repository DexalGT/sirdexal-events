package dev.sirdexal.events;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.entity.Entity;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import dev.sirdexal.events.lava.LavaConfig;
import dev.sirdexal.events.lava.LavaGame;

/**
 * SirDexal Events — a server-side game mode ecosystem.
 *
 * <p>Current modes:
 * <ul><li><b>Lava Rising</b> — {@code /events lava start|stop|setup|config}</li></ul>
 * Future modes can be added under {@code /events <mode>}.
 */
public class SirdexalEvents implements ModInitializer {
    public static final String MOD_ID = "sirdexal_events";

    public static LavaConfig LAVA_CONFIG;
    public static LavaGame   LAVA_GAME;

    // ── World Reset ──
    private static int resetCountdown = -1;
    private static int ticksSinceLastSecond = 0;
    public static final String SERVER_UUID = "06c9e470-9f4f-4e01-b7e1-fa70b7265b4b";

    @Override
    public void onInitialize() {
        EventsLog.init();
        EventsLog.info("========================================");
        EventsLog.info("SirDexal Events v1.0.0 — initializing");
        EventsLog.info("========================================");

        // ── Lava Rising module ──
        try {
            LAVA_CONFIG = LavaConfig.load();
            LAVA_GAME   = new LavaGame(LAVA_CONFIG);
            EventsLog.info("Lava Rising config loaded.");
        } catch (Exception e) {
            EventsLog.error("Failed to load Lava Rising config — using defaults", e);
            LAVA_CONFIG = new LavaConfig();
            LAVA_GAME   = new LavaGame(LAVA_CONFIG);
        }

        // ── Server lifecycle ──
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                LAVA_GAME.attachServer(server);
                EventsLog.info("Server started — Lava Rising module ready.");
            } catch (Exception e) {
                EventsLog.error("Error during SERVER_STARTED setup", e);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            EventsLog.info("Server stopping — saving configs.");
            try { LAVA_CONFIG.save(); } catch (Exception e) { EventsLog.error("Config save error", e); }
            EventsLog.close();
        });

        // ── Player events ──
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            try { LAVA_GAME.onPlayerRespawn(newPlayer); }
            catch (Exception e) { EventsLog.error("Error in AFTER_RESPAWN handler", e); }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            try {
                if (entity instanceof ServerPlayerEntity player)
                    LAVA_GAME.onPlayerDeath(player);
            } catch (Exception e) {
                EventsLog.error("Error in AFTER_DEATH handler", e);
            }
        });

        // ── Tick loop ──
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try { LAVA_GAME.onTick(server); }
            catch (Exception e) { EventsLog.error("Error in tick loop", e); }

            if (resetCountdown > 0) {
                ticksSinceLastSecond++;
                if (ticksSinceLastSecond >= 20) {
                    ticksSinceLastSecond = 0;
                    resetCountdown--;
                    if (resetCountdown <= 0) {
                        resetCountdown = -1;
                        server.getPlayerManager().broadcast(Text.literal("RESETTING").formatted(Formatting.DARK_RED, Formatting.BOLD), false);
                        server.getPlayerManager().broadcast(Text.literal("[Events] World reset incoming — server will restart shortly!").formatted(Formatting.RED, Formatting.BOLD), false);
                        EventsLog.info("World-reset countdown finished — emitting trigger token for the watcher.");
                        EventsLog.slf4j().info("[EVENTS-RESET] SERVER:{}", SERVER_UUID);
                    } else {
                        server.getPlayerManager().broadcast(Text.literal("[Events] World reset in " + resetCountdown + "s — /events world reset cancel to stop.").formatted(Formatting.RED), false);
                    }
                }
            }
        });

        // ── Commands ──
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
            EventsLog.info("/events command tree registered (env={}).", environment.name());
        });

        EventsLog.info("Initialization complete.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Permission check (matching manhunt pattern for 1.21.11)
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean isOp(ServerCommandSource src) {
        Entity entity = src.getEntity();
        if (entity instanceof ServerPlayerEntity player) {
            return src.getServer().getPlayerManager()
                    .isOperator(new PlayerConfigEntry(player.getGameProfile()));
        }
        return true; // console / command blocks always allowed
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  /events command tree
    // ─────────────────────────────────────────────────────────────────────────

    private void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("events")
            .then(CommandManager.literal("lava")
                .requires(SirdexalEvents::isOp)

                // /events lava start
                .then(CommandManager.literal("start")
                    .executes(ctx -> run(ctx, "lava start", () -> LAVA_GAME.start())))

                // /events lava stop
                .then(CommandManager.literal("stop")
                    .executes(ctx -> run(ctx, "lava stop", () -> LAVA_GAME.stop())))

                // /events lava setup
                .then(CommandManager.literal("setup")
                    .executes(ctx -> run(ctx, "lava setup", () -> LAVA_GAME.showSetup(ctx.getSource()))))

                // /events lava teams <join|leave|auto>
                .then(CommandManager.literal("teams")
                    .then(CommandManager.literal("join")
                        .then(CommandManager.argument("team", StringArgumentType.word())
                            .executes(ctx -> {
                                String team = StringArgumentType.getString(ctx, "team");
                                ServerPlayerEntity player = ctx.getSource().getPlayer();
                                if (player == null) return 0;
                                boolean success = LAVA_GAME.getTeams().joinTeam(ctx.getSource().getServer(), player, team);
                                if (success) ctx.getSource().sendFeedback(() -> Text.literal("[Events] Joined team " + team).formatted(Formatting.GREEN), false);
                                else ctx.getSource().sendError(Text.literal("[Events] Team not found. Valid teams: red, blue, green"));
                                return success ? 1 : 0;
                            })))
                    .then(CommandManager.literal("leave")
                        .executes(ctx -> {
                            ServerPlayerEntity player = ctx.getSource().getPlayer();
                            if (player == null) return 0;
                            LAVA_GAME.getTeams().leaveTeam(ctx.getSource().getServer(), player);
                            ctx.getSource().sendFeedback(() -> Text.literal("[Events] Left team").formatted(Formatting.GREEN), false);
                            return 1;
                        }))
                    .then(CommandManager.literal("auto")
                        .executes(ctx -> {
                            LAVA_GAME.getTeams().autoAssign(ctx.getSource().getServer(), LAVA_CONFIG.teamCount);
                            ctx.getSource().sendFeedback(() -> Text.literal("[Events] Auto-assigned remaining players to teams!").formatted(Formatting.GREEN), true);
                            return 1;
                        }))
                )

                // /events world reset
                .then(CommandManager.literal("world")
                    .then(CommandManager.literal("reset")
                        .executes(ctx -> {
                            if (resetCountdown > 0) {
                                ctx.getSource().sendError(Text.literal("[Events] A reset is already counting down (" + resetCountdown + "s left). Use /events world reset cancel to stop it."));
                                return 0;
                            }
                            resetCountdown = 10;
                            ticksSinceLastSecond = 0;
                            EventsLog.warn("RESET countdown STARTED ({}s).", resetCountdown);
                            ctx.getSource().getServer().getPlayerManager().broadcast(Text.literal("[Events] World reset in 10s — /events world reset cancel to stop.").formatted(Formatting.RED), false);
                            return 1;
                        })
                        .then(CommandManager.literal("cancel")
                            .executes(ctx -> {
                                if (resetCountdown <= 0) {
                                    ctx.getSource().sendError(Text.literal("[Events] No world reset is in progress."));
                                    return 0;
                                }
                                EventsLog.info("RESET countdown CANCELLED (was at {}s).", resetCountdown);
                                resetCountdown = -1;
                                ctx.getSource().getServer().getPlayerManager().broadcast(Text.literal("[Events] World reset cancelled.").formatted(Formatting.GREEN), false);
                                return 1;
                            })
                        )
                    )
                )

                // /events lava config <key> <value>
                .then(CommandManager.literal("config")

                    .then(CommandManager.literal("grace")
                        .then(CommandManager.argument("seconds", IntegerArgumentType.integer(60, 7200))
                            .executes(ctx -> setConfig(ctx, "Grace period",
                                    IntegerArgumentType.getInteger(ctx, "seconds") + "s",
                                    () -> LAVA_CONFIG.gracePeriod = IntegerArgumentType.getInteger(ctx, "seconds")))))

                    .then(CommandManager.literal("starter")
                        .then(CommandManager.argument("seconds", IntegerArgumentType.integer(10, 600))
                            .executes(ctx -> setConfig(ctx, "Starter period",
                                    IntegerArgumentType.getInteger(ctx, "seconds") + "s",
                                    () -> LAVA_CONFIG.starterPeriod = IntegerArgumentType.getInteger(ctx, "seconds")))))

                    .then(CommandManager.literal("rise_ticks")
                        .then(CommandManager.argument("ticks", IntegerArgumentType.integer(10, 600))
                            .executes(ctx -> setConfig(ctx, "Rise ticks",
                                    String.valueOf(IntegerArgumentType.getInteger(ctx, "ticks")),
                                    () -> LAVA_CONFIG.riseTicks = IntegerArgumentType.getInteger(ctx, "ticks")))))

                    .then(CommandManager.literal("rise_height")
                        .then(CommandManager.argument("y", IntegerArgumentType.integer(-64, 320))
                            .executes(ctx -> setConfig(ctx, "Rise height limit",
                                    "Y:" + IntegerArgumentType.getInteger(ctx, "y"),
                                    () -> LAVA_CONFIG.riseHeightLimit = IntegerArgumentType.getInteger(ctx, "y")))))

                    .then(CommandManager.literal("teams")
                        .then(CommandManager.literal("off")
                            .executes(ctx -> setConfig(ctx, "Teams", "disabled",
                                    () -> LAVA_CONFIG.teamsEnabled = false)))
                        .then(CommandManager.literal("2")
                            .executes(ctx -> setConfig(ctx, "Teams", "2 (Red vs Blue)",
                                    () -> { LAVA_CONFIG.teamsEnabled = true; LAVA_CONFIG.teamCount = 2; })))
                        .then(CommandManager.literal("3")
                            .executes(ctx -> setConfig(ctx, "Teams", "3 (Red vs Blue vs Green)",
                                    () -> { LAVA_CONFIG.teamsEnabled = true; LAVA_CONFIG.teamCount = 3; }))))

                    .then(CommandManager.literal("cut_clean")
                        .then(CommandManager.literal("on")
                            .executes(ctx -> setConfig(ctx, "Cut Clean", "ON", () -> LAVA_CONFIG.cutClean = true)))
                        .then(CommandManager.literal("off")
                            .executes(ctx -> setConfig(ctx, "Cut Clean", "OFF", () -> LAVA_CONFIG.cutClean = false))))

                    .then(CommandManager.literal("speed_uhc")
                        .then(CommandManager.literal("on")
                            .executes(ctx -> setConfig(ctx, "Speed UHC", "ON", () -> LAVA_CONFIG.speedUhc = true)))
                        .then(CommandManager.literal("off")
                            .executes(ctx -> setConfig(ctx, "Speed UHC", "OFF", () -> LAVA_CONFIG.speedUhc = false))))

                    .then(CommandManager.literal("sfx")
                        .then(CommandManager.literal("on")
                            .executes(ctx -> setConfig(ctx, "SFX", "ON", () -> LAVA_CONFIG.sfx = true)))
                        .then(CommandManager.literal("off")
                            .executes(ctx -> setConfig(ctx, "SFX", "OFF", () -> LAVA_CONFIG.sfx = false))))
                )
            )
        );
    }

    // ─── Command helpers ───

    private int run(CommandContext<ServerCommandSource> ctx, String label, Runnable action) {
        String caller = ctx.getSource().getName();
        EventsLog.info("CMD  /events {}  (by {})", label, caller);
        try {
            action.run();
            return 1;
        } catch (Exception e) {
            EventsLog.error("Command '/events " + label + "' FAILED (caller=" + caller + ")", e);
            ctx.getSource().sendError(Text.literal("[Events] Error: " + e.getMessage()));
            return 0;
        }
    }

    private int setConfig(CommandContext<ServerCommandSource> ctx, String key, String display, Runnable setter) {
        String caller = ctx.getSource().getName();
        EventsLog.info("CONFIG  {} = {}  (by {})", key, display, caller);
        try {
            setter.run();
            LAVA_CONFIG.save();
            ctx.getSource().sendFeedback(
                    () -> Text.literal("[Events] ").formatted(Formatting.DARK_GRAY)
                            .append(Text.literal(key + " set to " + display).formatted(Formatting.GREEN)),
                    true);
            return 1;
        } catch (Exception e) {
            EventsLog.error("Config set failed: " + key, e);
            ctx.getSource().sendError(Text.literal("[Events] Config error: " + e.getMessage()));
            return 0;
        }
    }
}
