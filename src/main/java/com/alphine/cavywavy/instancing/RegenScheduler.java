package com.alphine.cavywavy.instancing;

import com.alphine.cavywavy.config.CavyOreConfig;
import com.alphine.cavywavy.network.CavyNetworkHandler;
import com.alphine.cavywavy.network.PacketShowOre;
import com.alphine.cavywavy.util.OreGeneratorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.Iterator;
import java.util.Map;

@Mod.EventBusSubscriber
public class RegenScheduler {

    // Cooldown time in milliseconds (30 seconds)
    public static final long COOLDOWN_TIME_MS = 30_000L;

    // How often to update the visual cooldown indicator (every 5 seconds)
    private static final long VISUAL_UPDATE_INTERVAL_MS = 5_000L;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) return;

        long currentTime = System.currentTimeMillis();

        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (ServerPlayer player : level.players()) {
                Map<BlockPos, PlayerOreInstance> instances = InstanceManager.getAllInstances(player);
                if (instances.isEmpty()) continue;

                Iterator<Map.Entry<BlockPos, PlayerOreInstance>> iterator = instances.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<BlockPos, PlayerOreInstance> entry = iterator.next();
                    BlockPos pos = entry.getKey();
                    PlayerOreInstance instance = entry.getValue();

                    long regenTime = instance.getRegenAtMillis();
                    long timeRemaining = regenTime - currentTime;

                    // If it's time to regenerate
                    if (timeRemaining <= 0) {
                        // Pick a new ore block
                        Block newOre = CavyOreConfig.getRandomOre();

                        // Update NBT Data
                        OreGeneratorManager.addGenerator(pos, newOre);

                        // Reset the instance with updated timestamp
                        InstanceManager.setInstance(player, pos, newOre, currentTime + COOLDOWN_TIME_MS);

                        // Send updated fake ore block to the player
                        CavyNetworkHandler.CHANNEL.send(
                                PacketDistributor.PLAYER.with(() -> player),
                                new PacketShowOre(pos, newOre.defaultBlockState())
                        );

                        // Add particles to indicate the ore has regenerated
                        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, 
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                            10, 0.3, 0.3, 0.3, 0.01);
                    } 
                    // Show visual cooldown updates at intervals
                    else if (timeRemaining % VISUAL_UPDATE_INTERVAL_MS < 50) { // 50ms window to catch the update
                        // Send a visual update to show the current state (bedrock during cooldown)
                        CavyNetworkHandler.CHANNEL.send(
                                PacketDistributor.PLAYER.with(() -> player),
                                new PacketShowOre(pos, Blocks.BEDROCK.defaultBlockState())
                        );

                        // Add particles to indicate the ore is still in cooldown
                        level.sendParticles(ParticleTypes.SMOKE, 
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                            10, 0.3, 0.3, 0.3, 0.01);

                        // Add some flame particles to make it more obvious
                        level.sendParticles(ParticleTypes.FLAME, 
                            pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                            3, 0.2, 0.2, 0.2, 0.01);
                    }
                }
            }
        }
    }
}
