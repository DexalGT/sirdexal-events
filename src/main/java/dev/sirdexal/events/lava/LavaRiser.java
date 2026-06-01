package dev.sirdexal.events.lava;

import dev.sirdexal.events.EventsLog;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.List;

/**
 * Lava placement engine — replaces the old armor-stand "riser" entity approach.
 * Fills loaded chunks within the world border at the current lava level.
 */
public class LavaRiser {
    private final LavaConfig config;
    private int lavaLevel = -65;          // one below start so first rise → -64
    private int ticksSinceLastRise = 0;

    public LavaRiser(LavaConfig config) {
        this.config = config;
    }

    public void reset() {
        lavaLevel = -65;
        ticksSinceLastRise = 0;
    }

    public int getLavaLevel() {
        return lavaLevel;
    }

    /** Called every server tick during the RISING state. */
    public void onTick(MinecraftServer server) {
        ticksSinceLastRise++;
        if (ticksSinceLastRise >= config.riseTicks && lavaLevel < config.riseHeightLimit) {
            ticksSinceLastRise = 0;
            rise(server);
        }
    }

    // ─── Core rise logic ───

    private void rise(MinecraftServer server) {
        lavaLevel++;
        ServerWorld world = server.getOverworld();
        WorldBorder border = world.getWorldBorder();

        // Clamp fill area to world border (max ±1000 for safety)
        int minX = Math.max((int) Math.floor(border.getBoundWest()), -1000);
        int maxX = Math.min((int) Math.ceil(border.getBoundEast()), 1000);
        int minZ = Math.max((int) Math.floor(border.getBoundNorth()), -1000);
        int maxZ = Math.min((int) Math.ceil(border.getBoundSouth()), 1000);

        int minCX = minX >> 4, maxCX = maxX >> 4;
        int minCZ = minZ >> 4, maxCZ = maxZ >> 4;

        int blocksSet = 0;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                Chunk chunk = world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;

                int sx = Math.max(cx << 4, minX);
                int ex = Math.min((cx << 4) + 15, maxX);
                int sz = Math.max(cz << 4, minZ);
                int ez = Math.min((cz << 4) + 15, maxZ);

                for (int x = sx; x <= ex; x++) {
                    for (int z = sz; z <= ez; z++) {
                        BlockPos pos = new BlockPos(x, lavaLevel, z);
                        BlockState state = world.getBlockState(pos);
                        if (state.isOf(Blocks.BEDROCK) || state.isOf(Blocks.LAVA)) continue;
                        world.setBlockState(pos, Blocks.LAVA.getDefaultState(), Block.NOTIFY_ALL);
                        blocksSet++;
                    }
                }
            }
        }

        // Clear illegal blocks above the lava (water, kelp, seagrass, etc.)
        if (config.clearIllegalBlocks) {
            clearIllegalBlocks(world, minX, maxX, minZ, maxZ);
        }

        // Play lava pop sound via command (vanilla-compatible)
        if (config.sfx) {
            try {
                server.getCommandManager().getDispatcher().execute(
                        "execute as @a at @s run playsound minecraft:block.lava.pop block @s",
                        server.getCommandSource());
            } catch (Exception ignored) {}
        }

        EventsLog.debug("RISE: Y={} blocks={}", lavaLevel, blocksSet);
    }

    private void clearIllegalBlocks(ServerWorld world, int minX, int maxX, int minZ, int maxZ) {
        for (int dy = 1; dy <= 3; dy++) {
            int y = lavaLevel + dy;
            int minCX = minX >> 4, maxCX = maxX >> 4;
            int minCZ = minZ >> 4, maxCZ = maxZ >> 4;

            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    Chunk chunk = world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (chunk == null) continue;

                    int sx = Math.max(cx << 4, minX);
                    int ex = Math.min((cx << 4) + 15, maxX);
                    int sz = Math.max(cz << 4, minZ);
                    int ez = Math.min((cz << 4) + 15, maxZ);

                    for (int x = sx; x <= ex; x++) {
                        for (int z = sz; z <= ez; z++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            if (isIllegalBlock(world.getBlockState(pos))) {
                                world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isIllegalBlock(BlockState state) {
        return state.isOf(Blocks.WATER)
                || state.isOf(Blocks.KELP_PLANT) || state.isOf(Blocks.KELP)
                || state.isOf(Blocks.SEAGRASS) || state.isOf(Blocks.TALL_SEAGRASS)
                || state.isOf(Blocks.SEA_PICKLE)
                || state.isOf(Blocks.TUBE_CORAL) || state.isOf(Blocks.BRAIN_CORAL)
                || state.isOf(Blocks.BUBBLE_CORAL) || state.isOf(Blocks.FIRE_CORAL)
                || state.isOf(Blocks.HORN_CORAL)
                || state.isOf(Blocks.TUBE_CORAL_WALL_FAN) || state.isOf(Blocks.BRAIN_CORAL_WALL_FAN)
                || state.isOf(Blocks.BUBBLE_CORAL_WALL_FAN) || state.isOf(Blocks.FIRE_CORAL_WALL_FAN)
                || state.isOf(Blocks.HORN_CORAL_WALL_FAN);
    }

    // ─── Performance cleanup ───

    public void performanceCleanup(MinecraftServer server) {
        ServerWorld world = server.getOverworld();

        if (config.killAllFallingBlocks) {
            for (Entity e : world.iterateEntities()) {
                if (e instanceof FallingBlockEntity) e.discard();
            }
        } else if (config.killNearbyFallingBlocks) {
            WorldBorder border = world.getWorldBorder();
            double minX = border.getBoundWest();
            double maxX = border.getBoundEast();
            double minZ = border.getBoundNorth();
            double maxZ = border.getBoundSouth();
            
            Box area = new Box(minX, lavaLevel - 2, minZ, maxX, lavaLevel + config.killNearbyDistance, maxZ);
            List<FallingBlockEntity> blocks = world.getEntitiesByClass(FallingBlockEntity.class, area, e -> true);
            for (FallingBlockEntity fb : blocks) fb.discard();
        }
    }
}
