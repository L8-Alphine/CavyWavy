package com.alphine.cavywavy.instancing;

import com.alphine.cavywavy.network.CavyNetworkHandler;
import com.alphine.cavywavy.network.PacketRequestOreSync;
import com.alphine.cavywavy.network.PacketShowOre;
import com.alphine.cavywavy.util.OreGeneratorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks and manages ore instances per player.
 * Handles regeneration timers and fake ore displays.
 */
public class InstanceManager {

    private static final Map<UUID, Map<BlockPos, PlayerOreInstance>> PLAYER_INSTANCES = new HashMap<>();

    public static void init() {
        MinecraftForge.EVENT_BUS.register(new Object() {
            @SubscribeEvent
            public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
                if (event.getEntity() instanceof ServerPlayer serverPlayer) {
                    // Only send ore sync if generators exist
                    if (!OreGeneratorManager.getAllGeneratorPositions().isEmpty()) {
                        CavyNetworkHandler.CHANNEL.sendTo(
                                new PacketRequestOreSync(),
                                serverPlayer.connection.connection,
                                NetworkDirection.PLAY_TO_CLIENT
                        );
                    }
                }
            }
        });
    }

    public static void setInstance(ServerPlayer player, BlockPos pos, Block oreBlock, long regenAtMillis) {
        PLAYER_INSTANCES
                .computeIfAbsent(player.getUUID(), k -> new HashMap<>())
                .put(pos.immutable(), new PlayerOreInstance(oreBlock, regenAtMillis));
    }

    public static PlayerOreInstance getInstance(ServerPlayer player, BlockPos pos) {
        Map<BlockPos, PlayerOreInstance> map = PLAYER_INSTANCES.get(player.getUUID());
        if (map == null) return null;
        return map.get(pos.immutable());
    }

    // TODO: Need to implement...
    public static void clearInstance(ServerPlayer player, BlockPos pos) {
        Map<BlockPos, PlayerOreInstance> map = PLAYER_INSTANCES.get(player.getUUID());
        if (map != null) {
            map.remove(pos.immutable());
        }
    }

    public static Map<BlockPos, PlayerOreInstance> getAllInstances(ServerPlayer player) {
        return PLAYER_INSTANCES.getOrDefault(player.getUUID(), Map.of());
    }

    public static void clearAll(ServerPlayer player) {
        PLAYER_INSTANCES.remove(player.getUUID());
    }

    // Method to clear and reset all instances when gen is removed
    public static void clearAllAt(BlockPos pos) {
        for (UUID playerId : PLAYER_INSTANCES.keySet()) {
            Map<BlockPos, PlayerOreInstance> playerMap = PLAYER_INSTANCES.get(playerId);
            if (playerMap != null && playerMap.containsKey(pos)) {
                playerMap.remove(pos);

                // Also send update to that player to show real block (e.g., bedrock or air)
                ServerPlayer player = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(playerId);
                if (player != null) {
                    BlockState realState = player.level.getBlockState(pos);
                    CavyNetworkHandler.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new PacketShowOre(pos, realState)
                    );
                }
            }
        }
    }
}
