package com.alphine.cavywavy.util;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityType;

import java.util.*;

public class GlowingMarkerManager {
    // Got this class to work but it's eh...
    // TODO: Need to change to glowing surrounding the block and update the locations after each placement or removal...
    private static final Map<UUID, List<Integer>> adminMarkers = new HashMap<>();

    public static void showMarkers(ServerPlayer player, Set<BlockPos> positions) {
        ServerLevel level = player.getLevel();
        List<Integer> entityIds = new ArrayList<>();

        for (BlockPos pos : positions) {
            AreaEffectCloud marker = new AreaEffectCloud(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            marker.setRadius(0.3f);
            marker.setDuration(6000);
            marker.setInvisible(true);
            marker.setGlowingTag(true);

            int id = marker.getId();
            Packet<?> spawnPacket = new ClientboundAddEntityPacket(marker);
            player.connection.send(spawnPacket);
            entityIds.add(id);
        }

        adminMarkers.put(player.getUUID(), entityIds);
    }

    public static void clearMarkers(ServerPlayer player) {
        List<Integer> ids = adminMarkers.remove(player.getUUID());
        if (ids != null && !ids.isEmpty()) {
            player.connection.send(new ClientboundRemoveEntitiesPacket(ids.stream().mapToInt(i -> i).toArray()));
        }
    }
}
