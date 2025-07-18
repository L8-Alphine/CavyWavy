package com.alphine.cavywavy.network;

import com.alphine.cavywavy.instancing.InstanceManager;
import net.minecraft.network.FriendlyByteBuf;
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

            InstanceManager.getAllInstances(player).forEach((pos, instance) -> {
                CavyNetworkHandler.CHANNEL.sendTo(
                        new PacketShowOre(pos, instance.getOreBlock().defaultBlockState()),
                        player.connection.connection,
                        NetworkDirection.PLAY_TO_CLIENT
                );
            });
        });

        context.setPacketHandled(true);
    }
}
