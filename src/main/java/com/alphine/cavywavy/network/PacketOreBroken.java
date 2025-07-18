package com.alphine.cavywavy.network;

import com.alphine.cavywavy.config.CavyOreConfig;
import com.alphine.cavywavy.instancing.InstanceManager;
import com.alphine.cavywavy.instancing.PlayerOreInstance;
import com.alphine.cavywavy.util.OreGeneratorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
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
            BlockPos pos = msg.pos;

            // Check if this is a valid ore generator position
            if (!OreGeneratorManager.isGenerator(pos)) return;

            PlayerOreInstance instance = InstanceManager.getInstance(player, pos);

            if (instance == null) {
                Block currentOre = OreGeneratorManager.getOreFor(pos);
                instance = new PlayerOreInstance(currentOre, System.currentTimeMillis());
                InstanceManager.setInstance(player, pos, currentOre, System.currentTimeMillis());
            }

            Block ore = instance.getOreBlock();
            BlockState state = ore.defaultBlockState();

            // --- DROP LOGIC ---
            LootContext.Builder builder = new LootContext.Builder(serverLevel)
                    .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                    .withParameter(LootContextParams.TOOL, player.getMainHandItem())
                    .withParameter(LootContextParams.THIS_ENTITY, player)
                    .withParameter(LootContextParams.BLOCK_STATE, state);

            List<ItemStack> drops = state.getDrops(builder);

            boolean hasSpace = true;
            for (ItemStack stack : drops) {
                if (!player.getInventory().add(stack.copy())) {
                    hasSpace = false;
                    break;
                }
            }

            if (!hasSpace) {
                player.sendSystemMessage(Component.literal("§cYou don't have enough inventory space!"));
                return;
            }

            for (ItemStack stack : drops) {
                player.getInventory().placeItemBackInInventory(stack);
            }

            // --- Show bedrock illusion only to this player ---
            CavyNetworkHandler.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PacketShowOre(pos, Blocks.BEDROCK.defaultBlockState())
            );

            // Add particles for cooldown
            serverLevel.sendParticles(ParticleTypes.SMOKE,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                15, 0.3, 0.3, 0.3, 0.01);
            serverLevel.sendParticles(ParticleTypes.FLAME,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                5, 0.2, 0.2, 0.2, 0.01);

            // --- REGEN LOGIC ---
            Block newOre = CavyOreConfig.getRandomOre();
            OreGeneratorManager.addGenerator(pos, newOre);
            InstanceManager.setInstance(player, pos, newOre, System.currentTimeMillis() +
                com.alphine.cavywavy.instancing.RegenScheduler.COOLDOWN_TIME_MS);
        });

        ctx.get().setPacketHandled(true);
    }

    public BlockPos getPos() {
        return pos;
    }
}
