package dev.sirdexal.events.lava;

import dev.sirdexal.events.EventsLog;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles Cut Clean (auto-smelt ores & food) and Speed UHC (Haste effect).
 */
public class LavaExtras {
    private final LavaConfig config;
    private int tickCounter = 0;

    // Cut clean: raw item → smelted item
    private static final Map<Item, SmeltEntry> SMELT_MAP = new HashMap<>();

    private record SmeltEntry(Item result, int multiplier) {}

    static {
        // Ores (2× output like the original datapack)
        SMELT_MAP.put(Items.RAW_IRON,   new SmeltEntry(Items.IRON_INGOT, 2));
        SMELT_MAP.put(Items.RAW_GOLD,   new SmeltEntry(Items.GOLD_INGOT, 2));
        SMELT_MAP.put(Items.RAW_COPPER, new SmeltEntry(Items.COPPER_INGOT, 2));
        // Food (1× output)
        SMELT_MAP.put(Items.PORKCHOP,   new SmeltEntry(Items.COOKED_PORKCHOP, 1));
        SMELT_MAP.put(Items.BEEF,       new SmeltEntry(Items.COOKED_BEEF, 1));
        SMELT_MAP.put(Items.CHICKEN,    new SmeltEntry(Items.COOKED_CHICKEN, 1));
        SMELT_MAP.put(Items.MUTTON,     new SmeltEntry(Items.COOKED_MUTTON, 1));
        SMELT_MAP.put(Items.RABBIT,     new SmeltEntry(Items.COOKED_RABBIT, 1));
        SMELT_MAP.put(Items.COD,        new SmeltEntry(Items.COOKED_COD, 1));
        SMELT_MAP.put(Items.SALMON,     new SmeltEntry(Items.COOKED_SALMON, 1));
    }

    public LavaExtras(LavaConfig config) {
        this.config = config;
    }

    /** Called every server tick during GRACE and RISING periods. */
    public void onTick(MinecraftServer server) {
        tickCounter++;
        if (tickCounter < 10) return; // process every 0.5s
        tickCounter = 0;

        if (config.cutClean)  processCutClean(server);
        if (config.speedUhc)  processSpeedUhc(server);
    }

    // ─── Cut Clean ───

    private void processCutClean(MinecraftServer server) {
        ServerWorld world = server.getOverworld();

        for (Entity entity : world.iterateEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            ItemStack stack = itemEntity.getStack();
            SmeltEntry entry = SMELT_MAP.get(stack.getItem());
            if (entry == null) continue;

            // Spawn smelted item at the same position
            int count = stack.getCount() * entry.multiplier;
            ItemEntity replacement = new ItemEntity(
                    world, itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(),
                    new ItemStack(entry.result, count));
            replacement.setPickupDelay(8);
            replacement.setVelocity(0, 0.05, 0);
            world.spawnEntity(replacement);

            // Smoke particle
            world.spawnParticles(ParticleTypes.SMOKE,
                    itemEntity.getX(), itemEntity.getY(), itemEntity.getZ(),
                    15, 0, 0, 0, 0.01);

            // Remove original
            itemEntity.discard();
        }
    }

    // ─── Speed UHC ───

    private void processSpeedUhc(MinecraftServer server) {
        // Apply Haste I to all survival players (re-applied every 0.5s for continuous coverage)
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (p.isSpectator()) continue;
            p.addStatusEffect(new StatusEffectInstance(
                    StatusEffects.HASTE, 30, 0, true, false, false));
        }
    }
}
