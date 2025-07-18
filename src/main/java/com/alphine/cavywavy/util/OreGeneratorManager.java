package com.alphine.cavywavy.util;

import com.alphine.cavywavy.Cavywavy;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

public class OreGeneratorManager {

    // Works but eh...
    // Class has methods for sync, async, and delay sync saving
    // TODO: Need to have the nbt data for that location update per player with per player data then clean on restart or player leave.

    private static final Set<BlockPos> generatorPositions = new HashSet<>();
    private static final Set<UUID> adminsInPlacerMode = new HashSet<>();
    private static final Set<BlockPos> dirtyGenerators = ConcurrentHashMap.newKeySet();
    private static final Map<BlockPos, String> generatorOreMap = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService saveExecutor = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> delayedSave = null;
    private static final long SAVE_DELAY_MS = 5000;
    private static Path saveFilePath;

    private static boolean loadedOnce = false;

    public static void load() {
        try {
            Path configDir = FMLPaths.CONFIGDIR.get();
            saveFilePath = configDir.resolve("cavywavy/ore_generators.nbt");

            if (!Files.exists(saveFilePath)) {
                generatorPositions.clear();
                loadedOnce = true;
                return;
            }

            try (InputStream in = Files.newInputStream(saveFilePath)) {
                CompoundTag tag = NbtIo.readCompressed(in);
                ListTag list = tag.getList("Generators", Tag.TAG_COMPOUND);
                for (Tag t : list) {
                    CompoundTag p = (CompoundTag) t;
                    BlockPos pos = new BlockPos(p.getInt("x"), p.getInt("y"), p.getInt("z"));
                    generatorPositions.add(pos);
                    if (p.contains("ore")) {
                        generatorOreMap.put(pos, p.getString("ore"));
                    }
                }
            }

            loadedOnce = true;
        } catch (Exception e) {
            Cavywavy.LOGGER.error("Failed to load ore generator data", e);
        }
    }

    // Extra Method from the rework...
    public static Block getOreFor(BlockPos pos) {
        String id = generatorOreMap.getOrDefault(pos, "minecraft:stone");
        return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
    }

    public static boolean isGenerator(BlockPos pos) {
        return generatorPositions.contains(pos);
    }

    public static Set<BlockPos> getAllGeneratorPositions() {
        return Set.copyOf(generatorPositions);
    }

    public static void addGenerator(BlockPos pos, Block block) {
        generatorPositions.add(pos.immutable());
        generatorOreMap.put(pos.immutable(), ForgeRegistries.BLOCKS.getKey(block).toString());
        dirtyGenerators.add(pos.immutable());
        scheduleSave();
    }

    public static void removeGenerator(Level level, BlockPos pos) {
        BlockPos immutable = pos.immutable();
        generatorPositions.remove(immutable);
        generatorOreMap.remove(immutable);
        dirtyGenerators.add(immutable);
        scheduleSave();
    }

    public static void toggleAdmin(UUID uuid) {
        if (adminsInPlacerMode.contains(uuid)) {
            adminsInPlacerMode.remove(uuid);
        } else {
            adminsInPlacerMode.add(uuid);
        }
    }

    public static boolean isAdmin(UUID uuid) {
        return adminsInPlacerMode.contains(uuid);
    }

    public static void saveNowIfNeeded() {
        if (!dirtyGenerators.isEmpty()) {
            try {
                saveInternal();
            } catch (IOException e) {
                Cavywavy.LOGGER.error("Immediate save failed!", e);
            }
        }
    }

    private static synchronized void saveInternal() throws IOException {
        if (dirtyGenerators.isEmpty()) return;

        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();

        for (BlockPos pos : generatorPositions) {
            CompoundTag posTag = new CompoundTag();
            posTag.putInt("x", pos.getX());
            posTag.putInt("y", pos.getY());
            posTag.putInt("z", pos.getZ());
            posTag.putString("ore", generatorOreMap.getOrDefault(pos, "minecraft:stone"));
            list.add(posTag);
        }

        tag.put("Generators", list);
        Files.createDirectories(saveFilePath.getParent());
        try (OutputStream out = Files.newOutputStream(saveFilePath)) {
            NbtIo.writeCompressed(tag, out);
        }

        dirtyGenerators.clear();
    }

    private static void scheduleSave() {
        if (!loadedOnce) return;

        if (delayedSave != null && !delayedSave.isDone()) {
            delayedSave.cancel(false);
        }

        delayedSave = saveExecutor.schedule(() -> {
            try {
                saveInternal(); // thread-safe internal save
            } catch (IOException e) {
                Cavywavy.LOGGER.error("Failed to save ore generator positions", e);
            }
        }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }
}
