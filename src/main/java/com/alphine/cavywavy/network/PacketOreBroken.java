package com.alphine.cavywavy.network;

import com.alphine.cavywavy.config.CavyOreConfig;
import com.alphine.cavywavy.instancing.InstanceManager;
import com.alphine.cavywavy.instancing.PlayerOreInstance;
import com.alphine.cavywavy.util.OreGeneratorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

public class PacketOreBroken {

    private final BlockPos pos;

    public PacketOreBroken(BlockPos pos) {
        this.pos = pos;
    }

    public static void encode(PacketOreBroken msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos);
    }

    public static PacketOreBroken decode(FriendlyByteBuf buf) {
        return new PacketOreBroken(buf.readBlockPos());
    }

    // TODO: Need to fix the issue where this method isn't handled correctly for drop and regeneration.

    public static void handle(PacketOreBroken msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ServerLevel serverLevel = player.getLevel();

            PlayerOreInstance instance = InstanceManager.getInstance(player, msg.pos);
            if (instance == null) return;

            Block ore = instance.getOreBlock();
            BlockState state = ore.defaultBlockState();

            // --- DROP LOGIC ---
            LootContext.Builder builder = new LootContext.Builder(serverLevel)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(msg.pos))
                    .withParameter(LootContextParams.TOOL, player.getMainHandItem())
                    .withParameter(LootContextParams.THIS_ENTITY, player)
                    .withParameter(LootContextParams.BLOCK_STATE, state);

            List<ItemStack> drops = state.getDrops(builder);
            for (ItemStack stack : drops) {
                player.getInventory().placeItemBackInInventory(stack);
            }

            // --- WORLD BLOCK TO BEDROCK ---
            serverLevel.setBlockAndUpdate(msg.pos, Blocks.BEDROCK.defaultBlockState());

            // --- VISUAL BEDROCK ILLUSION (optional, for consistency) ---
            CavyNetworkHandler.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new PacketShowOre(msg.pos, Blocks.BEDROCK.defaultBlockState())
            );

            // --- REGEN LOGIC ---
            Block newOre = CavyOreConfig.getRandomOre();
            OreGeneratorManager.addGenerator(msg.pos, newOre);
            InstanceManager.setInstance(player, msg.pos, newOre, 60_000L);
        });

        ctx.get().setPacketHandled(true);
    }

    public BlockPos getPos() {
        return pos;
    }
}
