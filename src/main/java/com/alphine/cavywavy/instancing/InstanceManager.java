package com.alphine.cavywavy.instancing;

import com.alphine.cavywavy.util.DebugLog;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
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
        // No-op: syncing is handled in ForgeEventHandlers via vanilla block update packets.
        DebugLog.info("InstanceManager.init: no-op");
    }

    public static void setInstance(ServerPlayer player, BlockPos pos, Block oreBlock, long regenAtMillis) {
        PLAYER_INSTANCES
                .computeIfAbsent(player.getUUID(), k -> new HashMap<>())
                .put(pos.immutable(), new PlayerOreInstance(oreBlock, regenAtMillis));
        DebugLog.info(player, "Instance set: pos=%s ore=%s regenAt=%d", pos, oreBlock, regenAtMillis);
    }

    public static PlayerOreInstance getInstance(ServerPlayer player, BlockPos pos) {
        Map<BlockPos, PlayerOreInstance> map = PLAYER_INSTANCES.get(player.getUUID());
        PlayerOreInstance inst = map != null ? map.get(pos.immutable()) : null;
        DebugLog.info(player, "Instance get: pos=%s hit=%s", pos, inst != null);
        return inst;
    }

    @SuppressWarnings({"unused", "UnusedDeclaration"})
    public static void clearInstance(ServerPlayer player, BlockPos pos) {
        Map<BlockPos, PlayerOreInstance> map = PLAYER_INSTANCES.get(player.getUUID());
        if (map != null) {
            map.remove(pos.immutable());
            DebugLog.info(player, "Instance cleared: pos=%s", pos);
        }
    }

    public static Map<BlockPos, PlayerOreInstance> getAllInstances(ServerPlayer player) {
        Map<BlockPos, PlayerOreInstance> res = PLAYER_INSTANCES.getOrDefault(player.getUUID(), Map.of());
        DebugLog.info(player, "GetAllInstances size=%d", res.size());
        return res;
    }

    public static void clearAll(ServerPlayer player) {
        Map<BlockPos, PlayerOreInstance> removed = PLAYER_INSTANCES.remove(player.getUUID());
        DebugLog.info(player, "ClearAll: removed=%d", removed == null ? 0 : removed.size());
    }

    // Method to clear and reset all instances when gen is removed
    public static void clearAllAt(BlockPos pos) {
        DebugLog.infoPos(pos, "ClearAllAt: scanning players=%d", PLAYER_INSTANCES.size());
        for (UUID playerId : PLAYER_INSTANCES.keySet()) {
            Map<BlockPos, PlayerOreInstance> playerMap = PLAYER_INSTANCES.get(playerId);
            if (playerMap != null && playerMap.containsKey(pos)) {
                playerMap.remove(pos);

                // Also send update to that player to show real block (e.g., bedrock or air)
                var server = ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player != null) {
                        BlockState realState = player.level.getBlockState(pos);
                        player.connection.send(new ClientboundBlockUpdatePacket(pos, realState));
                        DebugLog.info(player, "ClearAllAt: sent real state %s at %s", realState, pos);
                    }
                }
            }
        }
    }
}

// Add this to PlayerOreInstance class if not present:
// public long getRegenAtMillis() { return regenAtMillis; }
