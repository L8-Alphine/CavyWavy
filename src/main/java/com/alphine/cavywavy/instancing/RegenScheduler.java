package com.alphine.cavywavy.instancing;

import com.alphine.cavywavy.config.CavyOreConfig;
import com.alphine.cavywavy.network.CavyNetworkHandler;
import com.alphine.cavywavy.network.PacketShowOre;
import com.alphine.cavywavy.util.OreGeneratorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.Iterator;
import java.util.Map;

@Mod.EventBusSubscriber
public class RegenScheduler {

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) return;

        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                Map<BlockPos, PlayerOreInstance> instances = InstanceManager.getAllInstances(player);
                if (instances.isEmpty()) continue;

                Iterator<Map.Entry<BlockPos, PlayerOreInstance>> iterator = instances.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<BlockPos, PlayerOreInstance> entry = iterator.next();
                    BlockPos pos = entry.getKey();
                    PlayerOreInstance instance = entry.getValue();

                    // Skip if ore shouldn't regenerate yet
                    if (!instance.shouldRegenerate()) continue;

                    // Pick a new ore block
                    Block newOre = CavyOreConfig.getRandomOre();

                    // Update NBT Data
                    OreGeneratorManager.addGenerator(pos, newOre);

                    // Reset the instance with updated timestamp
                    InstanceManager.setInstance(player, pos, newOre, System.currentTimeMillis() + 60_000);

                    // Send updated fake ore block to the player
                    CavyNetworkHandler.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new PacketShowOre(pos, newOre.defaultBlockState())
                    );
                }
            }
        }
    }
}
