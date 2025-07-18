package com.alphine.cavywavy.handlers;

import com.alphine.cavywavy.Cavywavy;
import com.alphine.cavywavy.config.CavyOreConfig;
import com.alphine.cavywavy.instancing.InstanceManager;
import com.alphine.cavywavy.instancing.PlayerOreInstance;
import com.alphine.cavywavy.network.CavyNetworkHandler;
import com.alphine.cavywavy.network.PacketRequestOreSync;
import com.alphine.cavywavy.network.PacketShowOre;
import com.alphine.cavywavy.util.OreGeneratorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;

import java.util.List;

@Mod.EventBusSubscriber(modid = Cavywavy.MODID)
public class ForgeEventHandlers {

    // YAY.... EVENTS.....

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        CavyNetworkHandler.CHANNEL.sendTo(
                new PacketRequestOreSync(),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT
        );
    }

    @SubscribeEvent
    public static void onPlayerChangeDim(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        CavyNetworkHandler.CHANNEL.sendTo(
                new PacketRequestOreSync(),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT
        );
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        CavyNetworkHandler.CHANNEL.sendTo(
                new PacketRequestOreSync(),
                player.connection.connection,
                NetworkDirection.PLAY_TO_CLIENT
        );
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            InstanceManager.clearAll(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();

        if (player.getLevel().isClientSide) return;

        ItemStack held = event.getItemStack();
//        System.out.println("[DEBUG] Held: " + held + " | Tag: " + held.getTag());
        if (held.hasTag() && held.getTag().getBoolean("OrePlacerTool")) {
            if (!OreGeneratorManager.isAdmin(player.getUUID())) {
                player.sendSystemMessage(Component.literal("§cYou must be in admin mode to place generators."));
                event.setCanceled(true);
                return;
            }

            BlockPos targetPos = event.getPos().relative(event.getFace());
            ServerLevel level = (ServerLevel) player.level;

            BlockState existing = level.getBlockState(targetPos);
            if (!existing.isAir() && !existing.getMaterial().isReplaceable()) {
                player.sendSystemMessage(Component.literal("§cTarget block is not replaceable."));
                event.setCanceled(true);
                return;
            }


//            System.out.println("[DEBUG] Checking if generator exists at " + targetPos + " = " + OreGeneratorManager.isGenerator(targetPos));
            if (OreGeneratorManager.isGenerator(targetPos)) {
                player.sendSystemMessage(Component.literal("§cA generator already exists here."));
                event.setCanceled(true);
                return;
            }

            // Pick a random ore from config
            Block illusionOre = CavyOreConfig.getRandomOre();

            // Place actual illusion ore in the world
            level.setBlockAndUpdate(targetPos, illusionOre.defaultBlockState());

            // Register generator and persist to NBT
            OreGeneratorManager.addGenerator(targetPos, illusionOre);

            // Send a forced illusion to the player
            if (player instanceof ServerPlayer serverPlayer) {
                BlockState fakeState = illusionOre.defaultBlockState();
                serverPlayer.connection.send(new ClientboundBlockUpdatePacket(targetPos, fakeState));
            }

            // Particles (fire around bedrock)
            for (int i = 0; i < 20; i++) {
                double angle = 2 * Math.PI * i / 20;
                double dx = Math.cos(angle) * 0.5;
                double dz = Math.sin(angle) * 0.5;
                level.sendParticles(ParticleTypes.FLAME,
                        targetPos.getX() + 0.5 + dx,
                        targetPos.getY() + 0.5,
                        targetPos.getZ() + 0.5 + dz,
                        1, 0, 0, 0, 0.01);
            }

            player.sendSystemMessage(Component.literal("§aPlaced generator at §e" + targetPos));
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        BlockPos pos = event.getPos();
        Level level = player.level;

        if (!level.isClientSide && OreGeneratorManager.isAdmin(player.getUUID())) {
            if (OreGeneratorManager.isGenerator(pos)) {
                // Remove instance
                InstanceManager.clearAllAt(pos);

                // Remove block
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                OreGeneratorManager.removeGenerator(level, pos);
                player.sendSystemMessage(Component.literal("§cGenerator at §e" + pos + " §cremoved."));

                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        Level level = player.level;
        BlockPos pos = event.getPos();

        if (!(level instanceof ServerLevel serverLevel)) return;

        if (OreGeneratorManager.isGenerator(pos) && !OreGeneratorManager.isAdmin(player.getUUID())) {
            event.setCanceled(true); // Don't break real bedrock

            PlayerOreInstance instance = InstanceManager.getInstance((ServerPlayer) player, pos);
            if (instance == null) return;

            // Give drops only to player
            List<ItemStack> drops = Block.getDrops(
                    instance.getOreBlock().defaultBlockState(),
                    serverLevel, pos, null, player, player.getMainHandItem());

            for (ItemStack stack : drops) {
                player.getInventory().placeItemBackInInventory(stack);
            }

            // Send fake bedrock back to this player
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.connection.send(new ClientboundBlockUpdatePacket(pos, Blocks.BEDROCK.defaultBlockState()));
            }

            // Schedule new ore regen for this player
            Block newOre = CavyOreConfig.getRandomOre();
            OreGeneratorManager.addGenerator(pos, newOre);
            InstanceManager.setInstance((ServerPlayer) player, pos, newOre, 60_000L);
        }
    }

}

