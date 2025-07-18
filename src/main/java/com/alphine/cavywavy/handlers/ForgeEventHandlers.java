package com.alphine.cavywavy.handlers;

import com.alphine.cavywavy.Cavywavy;
import com.alphine.cavywavy.config.CavyOreConfig;
import com.alphine.cavywavy.instancing.InstanceManager;
import com.alphine.cavywavy.instancing.PlayerOreInstance;
import com.alphine.cavywavy.network.CavyNetworkHandler;
import com.alphine.cavywavy.network.PacketRequestOreSync;
import com.alphine.cavywavy.network.PacketShowOre;
import com.alphine.cavywavy.network.PacketOreBroken;
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
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    // Set cooldown to 30 seconds
    public static final long COOLDOWN_TIME_MS = 30000;

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        Level level = player.level;
        BlockPos pos = event.getPos();

        if (!(level instanceof ServerLevel serverLevel)) return;

        if (OreGeneratorManager.isGenerator(pos) && !OreGeneratorManager.isAdmin(player.getUUID())) {
            event.setCanceled(true); // Prevent actual block break

            // --- Instance timer check ---
            var instance = InstanceManager.getInstance((ServerPlayer) player, pos);
            long now = System.currentTimeMillis();
            if (instance != null && now < instance.getRegenAtMillis()) {
                player.sendSystemMessage(Component.literal("§cThis generator is on cooldown!"));
                return;
            }

            // --- DROP LOGIC ---
            Block oreBlock = OreGeneratorManager.getOreFor(pos);
            BlockState state = oreBlock.defaultBlockState();

            net.minecraft.world.level.storage.loot.LootContext.Builder builder = new net.minecraft.world.level.storage.loot.LootContext.Builder(serverLevel)
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, net.minecraft.world.phys.Vec3.atCenterOf(pos))
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.TOOL, player.getMainHandItem())
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.THIS_ENTITY, player)
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_STATE, state);

            java.util.List<ItemStack> drops = state.getDrops(builder);

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

            // --- Instantly show bedrock illusion only to this player using packet magic ---
            if (player instanceof ServerPlayer serverPlayer) {
                // Only send for the block that was broken
                CavyNetworkHandler.CHANNEL.sendTo(
                    new PacketShowOre(pos, Blocks.BEDROCK.defaultBlockState()),
                    serverPlayer.connection.connection,
                    NetworkDirection.PLAY_TO_CLIENT
                );
            }

            // Add particles for cooldown
            serverLevel.sendParticles(ParticleTypes.SMOKE,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                15, 0.3, 0.3, 0.3, 0.01);
            serverLevel.sendParticles(ParticleTypes.FLAME,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                5, 0.2, 0.2, 0.2, 0.01);

            // --- REGEN LOGIC ---
            long regenAt = now + COOLDOWN_TIME_MS;
            InstanceManager.setInstance((ServerPlayer) player, pos, Blocks.BEDROCK, regenAt);

            // Schedule regen using tick event
            ForgeEventHandlers.pendingRegens.put(pos.immutable(), new ForgeEventHandlers.RegenTask(serverLevel, pos.immutable(), (ServerPlayer) player, regenAt));
        }
    }

    private static final Map<BlockPos, RegenTask> pendingRegens = new HashMap<>();

    private static class RegenTask {
        public final ServerLevel level;
        public final BlockPos pos;
        public final ServerPlayer player;
        public final long triggerTime;

        public RegenTask(ServerLevel level, BlockPos pos, ServerPlayer player, long triggerTime) {
            this.level = level;
            this.pos = pos;
            this.player = player;
            this.triggerTime = triggerTime;
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.currentTimeMillis();
        var iterator = pendingRegens.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            RegenTask task = entry.getValue();
            if (now >= task.triggerTime) {
                Block newOre = CavyOreConfig.getRandomOre();
                OreGeneratorManager.addGenerator(task.pos, newOre);
                InstanceManager.setInstance(task.player, task.pos, newOre, 0); // 0 = can break again

                // Send packet to update block visually for this player only
                CavyNetworkHandler.CHANNEL.sendTo(
                    new PacketShowOre(task.pos, newOre.defaultBlockState()),
                    task.player.connection.connection,
                    NetworkDirection.PLAY_TO_CLIENT
                );
                iterator.remove();
            }
        }
    }

}
