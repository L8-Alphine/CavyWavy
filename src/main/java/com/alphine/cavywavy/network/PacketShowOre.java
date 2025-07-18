package com.alphine.cavywavy.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

public class PacketShowOre {

    private final BlockPos pos;
    private final BlockState blockState;

    public PacketShowOre(BlockPos pos, BlockState blockState) {
        this.pos = pos;
        this.blockState = blockState;
    }

    public static void encode(PacketShowOre msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
        buf.writeNbt((CompoundTag) BlockState.CODEC.encodeStart(NbtOps.INSTANCE, msg.blockState).result().orElseThrow());
    }

    public static PacketShowOre decode(FriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        var nbt = buf.readNbt();
        BlockState state = BlockState.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, nbt).result().orElseThrow();
        return new PacketShowOre(pos, state);
    }

    // Method to handle the packet on both client and server sides
    public static void handle(PacketShowOre msg, Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            if (ctx.get().getDirection().getReceptionSide().isClient()) {
                // Client-side handling: Update ONLY the block visually at msg.pos
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level != null) {
                    mc.level.setBlock(msg.pos, msg.blockState, 19); // 19 = force client update, no physics
                }
            } else {
                // Server-side handling: Send update to the player
                ServerPlayer player = ctx.get().getSender();
                if (player != null) {
                    player.connection.send(new ClientboundBlockUpdatePacket(msg.pos, msg.blockState));
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
