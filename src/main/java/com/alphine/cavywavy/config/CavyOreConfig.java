package com.alphine.cavywavy.config;

import com.alphine.cavywavy.Cavywavy;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.*;

public class CavyOreConfig {

    // TODO: Need to add a reload method

    private static final File CONFIG_FILE = new File("config/cavywavy/ores.json");
    private static final List<WeightedOre> ORE_LIST = new ArrayList<>();
    private static final Random RNG = new Random();

    public static void load() {
        try {
            if (!CONFIG_FILE.exists()) {
                saveDefaultConfig();
                Cavywavy.LOGGER.info("[CavyOreConfig] Created default ores.json");
            }

            FileReader reader = new FileReader(CONFIG_FILE);
            Type type = new TypeToken<List<WeightedOre>>() {}.getType();
            List<WeightedOre> loaded = new Gson().fromJson(reader, type);
            reader.close();

            ORE_LIST.clear();
            for (WeightedOre ore : loaded) {
                if (ForgeRegistries.BLOCKS.containsKey(new ResourceLocation(ore.id))) {
                    ORE_LIST.add(ore);
                } else {
                    Cavywavy.LOGGER.warn("[CavyOreConfig] Skipping unknown block ID: {}", ore.id);
                }
            }

            if (ORE_LIST.isEmpty()) {
                Cavywavy.LOGGER.warn("[CavyOreConfig] No valid ore blocks loaded. Defaulting to stone.");
                ORE_LIST.add(new WeightedOre("minecraft:stone", 1));
            }

            Cavywavy.LOGGER.info("[CavyOreConfig] Loaded {} valid ores.", ORE_LIST.size());

        } catch (Exception e) {
            Cavywavy.LOGGER.error("[CavyOreConfig] Failed to load ores.json", e);
        }
    }

    private static void saveDefaultConfig() {
        try {
            CONFIG_FILE.getParentFile().mkdirs();
            FileWriter writer = new FileWriter(CONFIG_FILE);
            writer.write("""
                [
                  { "id": "minecraft:coal_ore", "weight": 10 },
                  { "id": "minecraft:iron_ore", "weight": 8 },
                  { "id": "minecraft:redstone_ore", "weight": 6 },
                  { "id": "minecraft:diamond_ore", "weight": 2 }
                ]
                """);
            writer.close();
        } catch (Exception e) {
            Cavywavy.LOGGER.error("[CavyOreConfig] Failed to write default config", e);
        }
    }

    public static Block getRandomOre() {
        int totalWeight = ORE_LIST.stream().mapToInt(o -> o.weight).sum();
        int r = RNG.nextInt(totalWeight);
        int cumulative = 0;

        for (WeightedOre ore : ORE_LIST) {
            cumulative += ore.weight;
            if (r < cumulative) {
                return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(ore.id));
            }
        }

        return Blocks.STONE; // fallback
    }

    private static class WeightedOre {
        public String id;
        public int weight;

        public WeightedOre(String id, int weight) {
            this.id = id;
            this.weight = weight;
        }
    }
}
