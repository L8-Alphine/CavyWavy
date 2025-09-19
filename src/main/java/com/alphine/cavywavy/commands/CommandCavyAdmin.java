package com.alphine.cavywavy.commands;

import com.alphine.cavywavy.config.CavyOreConfig;
import com.alphine.cavywavy.handlers.ForgeEventHandlers;
import com.alphine.cavywavy.util.DebugLog;
import com.alphine.cavywavy.util.GlowingMarkerManager;
import com.alphine.cavywavy.util.OreGeneratorManager;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.UUID;

public class CommandCavyAdmin {

    // Simple commands...


    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cavy")
                .requires(source -> source.hasPermission(2)) // Requires OP level 2+
                .then(Commands.literal("admin")
                        .then(Commands.literal("refreshores")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    DebugLog.info(player, "Command: refreshores invoked");
                                    ForgeEventHandlers.syncPlayerOres(player);
                                    ctx.getSource().sendSuccess(Component.literal("§aOre visuals refreshed."), false);
                                    return 1;
                                }))
                        .then(Commands.literal("debug")
                                .executes(ctx -> {
                                    boolean newVal = DebugLog.toggle();
                                    ctx.getSource().sendSuccess(Component.literal("§7Debug logging: " + (newVal ? "§aENABLED" : "§cDISABLED")), false);
                                    return 1;
                                })
                                .then(Commands.literal("on").executes(ctx -> {
                                    DebugLog.setEnabled(true);
                                    ctx.getSource().sendSuccess(Component.literal("§7Debug logging: §aENABLED"), false);
                                    return 1;
                                }))
                                .then(Commands.literal("off").executes(ctx -> {
                                    DebugLog.setEnabled(false);
                                    ctx.getSource().sendSuccess(Component.literal("§7Debug logging: §cDISABLED"), false);
                                    return 1;
                                }))
                                .then(Commands.literal("status").executes(ctx -> {
                                    boolean en = DebugLog.isEnabled();
                                    ctx.getSource().sendSuccess(Component.literal("§7Debug logging: " + (en ? "§aENABLED" : "§cDISABLED")), false);
                                    return 1;
                                }))
                        )
                        .then(Commands.literal("placer")
                                .executes(ctx -> {
                                    DebugLog.info("Command: placer toggle invoked by %s", ctx.getSource().getTextName());
                                    return togglePlacerMode(ctx.getSource());
                                })
                        )
                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    DebugLog.info("Command: reload invoked by %s", ctx.getSource().getTextName());
                                    CavyOreConfig.save();
                                    CavyOreConfig.reload();
                                    ctx.getSource().sendSuccess(Component.literal("§aOre File Reloaded."), false);
                                    return 1;
                                }))
                )
        );
    }

    private static int togglePlacerMode(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("§cOnly players can use this command."));
            return 0;
        }

        UUID uuid = player.getUUID();
        boolean wasActive = OreGeneratorManager.isAdmin(uuid);
        OreGeneratorManager.toggleAdmin(uuid);
        DebugLog.info(player, "Placer mode toggled: %s -> %s", wasActive, !wasActive);

        if (wasActive) {
            GlowingMarkerManager.clearMarkers(player);
            source.sendSuccess(Component.literal("§cAdmin placer mode disabled."), false);
        } else {
            // Show glowing markers
            GlowingMarkerManager.showMarkers(player, OreGeneratorManager.getAllGeneratorPositions());
            // Give placer item only if the player doesn't already have one
            boolean hasPlacer = player.getInventory().items.stream()
                    .anyMatch(stack -> stack.hasTag() && stack.getOrCreateTag().getBoolean("OrePlacerTool"));

            if (!hasPlacer) {
                ItemStack placer = new ItemStack(Items.BEDROCK); // Use any vanilla item
                placer.enchant(Enchantments.UNBREAKING, 1); // Just for glow
                placer.setHoverName(Component.literal("§r§b[Ore Generator Tool]"));

                CompoundTag tag = placer.getOrCreateTag();
                tag.putBoolean("OrePlacerTool", true);

                player.getInventory().add(placer);
                DebugLog.info(player, "Placer given");
            } else {
                DebugLog.info(player, "Placer already present");
            }

            source.sendSuccess(Component.literal("§aAdmin placer mode enabled."), false);
        }
        return 1;
    }
}
