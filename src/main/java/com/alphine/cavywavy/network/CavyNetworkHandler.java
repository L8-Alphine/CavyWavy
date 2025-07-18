package com.alphine.cavywavy.network;

import com.alphine.cavywavy.Cavywavy;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class CavyNetworkHandler {
    // Made sure to make this only require Server packets even though we send packets to the client
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Cavywavy.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION -> true,
            PROTOCOL_VERSION -> true
    );

    private static int packetId = 0;
    private static int nextId() {
        return packetId++;
    }

    public static void register() {
        CHANNEL.registerMessage(nextId(), PacketOreBroken.class,
                PacketOreBroken::encode,
                PacketOreBroken::decode,
                PacketOreBroken::handle
        );

        CHANNEL.registerMessage(nextId(), PacketShowOre.class,
                PacketShowOre::encode,
                PacketShowOre::decode,
                PacketShowOre::handle
        );

        CHANNEL.registerMessage(nextId(), PacketRequestOreSync.class,
                PacketRequestOreSync::encode,
                PacketRequestOreSync::decode,
                PacketRequestOreSync::handle
        );
    }
}
