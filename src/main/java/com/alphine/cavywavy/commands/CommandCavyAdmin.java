package com.alphine.cavywavy.commands;

import com.alphine.cavywavy.network.CavyNetworkHandler;
import com.alphine.cavywavy.network.PacketRequestOreSync;
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
import net.minecraftforge.network.NetworkDirection;

import java.util.UUID;

public class CommandCavyAdmin {

    // Simple commands...
    // TODO: Add a reload command for the ores.json file

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cavy")
                .requires(source -> source.hasPermission(2)) // Requires OP level 2+
                .then(Commands.literal("admin")
                        .then(Commands.literal("refreshores")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    CavyNetworkHandler.CHANNEL.sendTo(new PacketRequestOreSync(), player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
                                    ctx.getSource().sendSuccess(Component.literal("§aOre visuals refreshed."), false);
                                    return 1;
                                }))
                        .then(Commands.literal("placer")
                                .executes(ctx -> togglePlacerMode(ctx.getSource()))
                        )
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
            }

            source.sendSuccess(Component.literal("§aAdmin placer mode enabled."), false);
        }
        return 1;
    }
}
