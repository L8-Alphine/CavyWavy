package com.alphine.cavywavy.network;

import com.alphine.cavywavy.instancing.InstanceManager;
import com.alphine.cavywavy.util.OreGeneratorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PacketRequestOreSync {

    // Class is an extra packet class to update generator data using PacketShowOre class.

    // Empty constructor since no data is transmitted
    public PacketRequestOreSync() {}

    public static void encode(PacketRequestOreSync msg, FriendlyByteBuf buf) {
        // No data to encode
    }

    public static PacketRequestOreSync decode(FriendlyByteBuf buf) {
        return new PacketRequestOreSync();
    }

    public static void handle(PacketRequestOreSync msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();

        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null) return;

            // First, refresh existing instances
            InstanceManager.getAllInstances(player).forEach((pos, instance) -> {
                CavyNetworkHandler.CHANNEL.sendTo(
                        new PacketShowOre(pos, instance.getOreBlock().defaultBlockState()),
                        player.connection.connection,
                        NetworkDirection.PLAY_TO_CLIENT
                );
            });

            // Then, create instances for all generators that the player doesn't have yet
            for (BlockPos pos : OreGeneratorManager.getAllGeneratorPositions()) {
                if (InstanceManager.getInstance(player, pos) == null) {
                    Block oreBlock = OreGeneratorManager.getOreFor(pos);
                    // Create a new instance with the current ore and set it to regenerate immediately
                    InstanceManager.setInstance(player, pos, oreBlock, System.currentTimeMillis());
                    // Send the ore visual to the player
                    CavyNetworkHandler.CHANNEL.sendTo(
                            new PacketShowOre(pos, oreBlock.defaultBlockState()),
                            player.connection.connection,
                            NetworkDirection.PLAY_TO_CLIENT
                    );
                }
            }
        });

        context.setPacketHandled(true);
    }
}
