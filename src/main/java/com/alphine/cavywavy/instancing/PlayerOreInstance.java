package com.alphine.cavywavy.instancing;

import net.minecraft.world.level.block.Block;

public class PlayerOreInstance {
    private final Block oreBlock;
    private final long regenAtMillis;

    public PlayerOreInstance(Block oreBlock, long regenAtMillis) {
        this.oreBlock = oreBlock;
        this.regenAtMillis = regenAtMillis;
    }

    public Block getOreBlock() {
        return oreBlock;
    }

    public boolean shouldRegenerate() {
        return System.currentTimeMillis() >= regenAtMillis;
    }
}

