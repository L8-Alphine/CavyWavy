package com.alphine.cavywavy.util;

import com.alphine.cavywavy.Cavywavy;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
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
            DebugLog.info("OreGeneratorManager.load: path=%s", saveFilePath);

            if (!Files.exists(saveFilePath)) {
                generatorPositions.clear();
                loadedOnce = true;
                DebugLog.info("OreGeneratorManager.load: no file, cleared positions (0)");
                return;
            }

            try (InputStream in = Files.newInputStream(saveFilePath)) {
                CompoundTag tag = NbtIo.readCompressed(in);
                ListTag list = tag.getList("Generators", Tag.TAG_COMPOUND);
                generatorPositions.clear();
                generatorOreMap.clear();
                for (Tag t : list) {
                    CompoundTag p = (CompoundTag) t;
                    BlockPos pos = new BlockPos(p.getInt("x"), p.getInt("y"), p.getInt("z"));
                    generatorPositions.add(pos);
                    if (p.contains("ore")) {
                        generatorOreMap.put(pos, p.getString("ore"));
                    }
                }
                DebugLog.info("OreGeneratorManager.load: loaded gens=%d", generatorPositions.size());
            }

            loadedOnce = true;
        } catch (Exception e) {
            Cavywavy.LOGGER.error("Failed to load ore generator data", e);
        }
    }

    // Extra Method from the rework...
    public static Block getOreFor(BlockPos pos) {
        String id = generatorOreMap.getOrDefault(pos, "minecraft:stone");
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
        DebugLog.infoPos(pos, "getOreFor -> %s", b);
        return b;
    }

    public static boolean isGenerator(BlockPos pos) {
        boolean v = generatorPositions.contains(pos);
        DebugLog.infoPos(pos, "isGenerator -> %s", v);
        return v;
    }

    public static Set<BlockPos> getAllGeneratorPositions() {
        return Set.copyOf(generatorPositions);
    }

    public static void addGenerator(BlockPos pos, Block block) {
        BlockPos imm = pos.immutable();
        generatorPositions.add(imm);
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        String id = key != null ? key.toString() : "minecraft:stone";
        generatorOreMap.put(imm, id);
        dirtyGenerators.add(imm);
        DebugLog.infoPos(pos, "addGenerator ore=%s (gens now %d)", block, generatorPositions.size());
        scheduleSave();
    }

    public static void removeGenerator(BlockPos pos) {
        BlockPos immutable = pos.immutable();
        generatorPositions.remove(immutable);
        generatorOreMap.remove(immutable);
        dirtyGenerators.add(immutable);
        DebugLog.infoPos(pos, "removeGenerator (gens now %d)", generatorPositions.size());
        scheduleSave();
    }

    public static void toggleAdmin(UUID uuid) {
        if (adminsInPlacerMode.contains(uuid)) {
            adminsInPlacerMode.remove(uuid);
            DebugLog.info("toggleAdmin: %s -> OFF", uuid);
        } else {
            adminsInPlacerMode.add(uuid);
            DebugLog.info("toggleAdmin: %s -> ON", uuid);
        }
    }

    public static boolean isAdmin(UUID uuid) {
        boolean on = adminsInPlacerMode.contains(uuid);
        DebugLog.info("isAdmin(%s) -> %s", uuid, on);
        return on;
    }

    public static void saveNowIfNeeded() {
        if (!dirtyGenerators.isEmpty()) {
            try {
                DebugLog.info("saveNowIfNeeded: dirty count=%d", dirtyGenerators.size());
                saveInternal();
            } catch (IOException e) {
                Cavywavy.LOGGER.error("Immediate save failed!", e);
            }
        }
    }

    private static synchronized void saveInternal() throws IOException {
        if (dirtyGenerators.isEmpty()) return;
        DebugLog.info("saveInternal: saving gens=%d dirty=%d", generatorPositions.size(), dirtyGenerators.size());

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
        DebugLog.info("saveInternal: completed");
    }

    private static void scheduleSave() {
        if (!loadedOnce) return;

        if (delayedSave != null && !delayedSave.isDone()) {
            delayedSave.cancel(false);
        }

        DebugLog.info("scheduleSave: scheduling in %d ms", SAVE_DELAY_MS);
        delayedSave = saveExecutor.schedule(() -> {
            try {
                saveInternal(); // thread-safe internal save
            } catch (IOException e) {
                Cavywavy.LOGGER.error("Failed to save ore generator positions", e);
            }
        }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS);
    }
}
