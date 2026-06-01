package dev.sirdexal.events.lava;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class LavaSetupGui extends GenericContainerScreenHandler {

    private final LavaConfig config;
    private final LavaGame game;
    private final Inventory guiInv;

    public LavaSetupGui(int syncId, PlayerInventory playerInventory, LavaConfig config, LavaGame game) {
        // Must pass a new SimpleInventory to the superclass to create the 9x3 grid
        super(ScreenHandlerType.GENERIC_9X3, syncId, playerInventory, new SimpleInventory(27), 3);
        this.config = config;
        this.game = game;
        this.guiInv = this.getInventory();
        updateGui();
    }

    private void updateGui() {
        guiInv.clear();

        // Slot 10: Grace Period (Clock)
        guiInv.setStack(10, createItem(Items.CLOCK, "Grace Period: " + config.gracePeriod + "s", 
                "Left-Click: +60s", "Right-Click: -60s"));

        // Slot 11: Starter Period (Compass)
        guiInv.setStack(11, createItem(Items.COMPASS, "Starter Period: " + config.starterPeriod + "s", 
                "Left-Click: +10s", "Right-Click: -10s"));

        // Slot 12: Rise Ticks (Lava Bucket)
        guiInv.setStack(12, createItem(Items.LAVA_BUCKET, "Rise Ticks: " + config.riseTicks, 
                "Left-Click: +10", "Right-Click: -10"));

        // Slot 14: Cut Clean (Furnace)
        guiInv.setStack(14, createItem(Items.FURNACE, "Cut Clean: " + (config.cutClean ? "ON" : "OFF"), 
                "Click to Toggle"));

        // Slot 15: Speed UHC (Golden Pickaxe)
        guiInv.setStack(15, createItem(Items.GOLDEN_PICKAXE, "Speed UHC: " + (config.speedUhc ? "ON" : "OFF"), 
                "Click to Toggle"));

        // Slot 16: Teams (Red/Blue/Green Wool)
        String teamsText = config.teamsEnabled ? (config.teamCount + " Teams") : "OFF";
        guiInv.setStack(16, createItem(Items.WHITE_WOOL, "Teams: " + teamsText, 
                "Click to Cycle (OFF -> 2 -> 3)"));

        // Slot 26: Start Game (Green Wool)
        guiInv.setStack(26, createItem(Items.LIME_WOOL, "START GAME", 
                "Click to launch Lava Rising!"));
    }

    private ItemStack createItem(net.minecraft.item.Item item, String name, String... lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME, Text.literal(name).formatted(Formatting.YELLOW, Formatting.BOLD));
        
        if (lore.length > 0) {
            java.util.List<Text> loreLines = new java.util.ArrayList<>();
            for (String l : lore) {
                loreLines.add(Text.literal(l).formatted(Formatting.GRAY));
            }
            stack.set(net.minecraft.component.DataComponentTypes.LORE, new net.minecraft.component.type.LoreComponent(loreLines));
        }
        return stack;
    }

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        // Prevent all item moving
        if (slotIndex >= 0 && slotIndex < 27) {
            handleInteraction(slotIndex, button == 0, player);
            updateGui();
        }
        // Send a packet to update the cursor to prevent ghost items
        if (player instanceof ServerPlayerEntity sp) {
            sp.currentScreenHandler.sendContentUpdates();
            sp.currentScreenHandler.updateToClient();
        }
    }

    private void handleInteraction(int slot, boolean leftClick, PlayerEntity player) {
        switch (slot) {
            case 10 -> config.gracePeriod = Math.max(60, config.gracePeriod + (leftClick ? 60 : -60));
            case 11 -> config.starterPeriod = Math.max(10, config.starterPeriod + (leftClick ? 10 : -10));
            case 12 -> config.riseTicks = Math.max(10, config.riseTicks + (leftClick ? 10 : -10));
            case 14 -> config.cutClean = !config.cutClean;
            case 15 -> config.speedUhc = !config.speedUhc;
            case 16 -> {
                if (!config.teamsEnabled) {
                    config.teamsEnabled = true;
                    config.teamCount = 2;
                } else if (config.teamCount == 2) {
                    config.teamCount = 3;
                } else {
                    config.teamsEnabled = false;
                }
            }
            case 26 -> {
                config.save();
                game.start();
                if (player instanceof ServerPlayerEntity sp) {
                    sp.closeHandledScreen();
                }
            }
        }
        config.save();
    }
    
    // Quick fix: overriding the method that processes clicks is better in fabric 1.21.11.
    // However, onSlotClick might not be sufficient if actionType is QUICK_MOVE.
    // To be perfectly safe, we override `quickMove` and `canUse`.
    
    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return true;
    }
}
