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

    // Method works... surprisingly
    public static void handle(PacketShowOre msg, Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Server is faking this packet from itself
            ServerPlayer player = ctx.get().getSender();
            if (player != null) {
                player.connection.send(new ClientboundBlockUpdatePacket(msg.pos, msg.blockState));
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
