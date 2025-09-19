package com.alphine.cavywavy.handlers;

import com.alphine.cavywavy.Cavywavy;
import com.alphine.cavywavy.config.CavyOreConfig;
import com.alphine.cavywavy.instancing.InstanceManager;
import com.alphine.cavywavy.instancing.PlayerOreInstance;
import com.alphine.cavywavy.util.DebugLog;
import com.alphine.cavywavy.util.OreGeneratorManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.tags.BlockTags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

@Mod.EventBusSubscriber(modid = Cavywavy.MODID)
public class ForgeEventHandlers {

    // Simulated mining sessions and regen tasks
    private static final Map<UUID, MiningSession> ACTIVE_MINES = new HashMap<>();
    private static final long MINING_IDLE_TIMEOUT_MS = 350L; // cancel if no pulses for this time

    private static class MiningSession {
        final UUID playerId;
        final ServerLevel level;
        final BlockPos pos;
        final BlockState state;
        final boolean tierSufficient;
        final ItemStack toolSnapshot;
        final long startAt;
        final long finishAt;
        long lastPulseAt;
        int pulses;
        MiningSession(UUID playerId, ServerLevel level, BlockPos pos, BlockState state,
                      boolean tierSufficient, ItemStack toolSnapshot, long startAt, long finishAt) {
            this.playerId = playerId;
            this.level = level;
            this.pos = pos.immutable();
            this.state = state;
            this.tierSufficient = tierSufficient;
            this.toolSnapshot = toolSnapshot.copy();
            this.startAt = startAt;
            this.finishAt = finishAt;
            this.lastPulseAt = startAt;
            this.pulses = 1;
        }
    }

    // Queue: visual updates for just-placed generators to be sent next tick (ensures our illusion overrides vanilla bedrock update packet order)
    private static final Set<PlacementKey> PENDING_PLACEMENT_VISUALS = new HashSet<>();
    private record PlacementKey(net.minecraft.resources.ResourceKey<Level> dim, BlockPos pos) {
        public PlacementKey {
            pos = pos.immutable();
        }
    }

    // YAY.... EVENTS.....

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        DebugLog.info(player, "onPlayerJoin: syncing ores, gens=%d", OreGeneratorManager.getAllGeneratorPositions().size());
        syncPlayerOres(player);
    }

    @SubscribeEvent
    public static void onPlayerChangeDim(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        DebugLog.info(player, "onPlayerChangedDimension: syncing ores, gens=%d", OreGeneratorManager.getAllGeneratorPositions().size());
        syncPlayerOres(player);
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        DebugLog.info(player, "onPlayerRespawn: syncing ores, gens=%d", OreGeneratorManager.getAllGeneratorPositions().size());
        syncPlayerOres(player);
    }

    @SubscribeEvent
    public static void onChunkWatch(ChunkWatchEvent.Watch event) {
        ServerPlayer player = event.getPlayer();
        ChunkPos watched = event.getPos();
        long now = System.currentTimeMillis();
        DebugLog.info(player, "onChunkWatch: chunk=%s", watched);
        for (BlockPos pos : OreGeneratorManager.getAllGeneratorPositions()) {
            if ((pos.getX() >> 4) == watched.x && (pos.getZ() >> 4) == watched.z) {
                PlayerOreInstance inst = InstanceManager.getInstance(player, pos);
                if (inst == null) {
                    Block ore = OreGeneratorManager.getOreFor(pos);
                    DebugLog.info(player, "onChunkWatch: creating instance at %s with ore=%s", pos, ore);
                    InstanceManager.setInstance(player, pos, ore, 0);
                    player.connection.send(new ClientboundBlockUpdatePacket(pos, ore.defaultBlockState()));
                } else {
                    if (now < inst.getRegenAtMillis()) {
                        DebugLog.info(player, "onChunkWatch: cooldown visual bedrock at %s (remain=%dms)", pos, inst.getRegenAtMillis() - now);
                        player.connection.send(new ClientboundBlockUpdatePacket(pos, Blocks.BEDROCK.defaultBlockState()));
                    } else {
                        DebugLog.info(player, "onChunkWatch: showing ore at %s ore=%s", pos, inst.getOreBlock());
                        player.connection.send(new ClientboundBlockUpdatePacket(pos, inst.getOreBlock().defaultBlockState()));
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DebugLog.info(player, "onPlayerLoggedOut: clearing instances and pending regens/mines (regen=%d mines=%d)", pendingRegens.size(), ACTIVE_MINES.size());
            InstanceManager.clearAll(player);
            pendingRegens.keySet().removeIf(k -> k.playerId.equals(player.getUUID()));
            ACTIVE_MINES.remove(player.getUUID());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        if (player.getLevel().isClientSide) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        ItemStack held = event.getItemStack();
        var tag = held.getTag();
        if (tag != null && tag.getBoolean("OrePlacerTool")) {
            DebugLog.info((ServerPlayer) player, "RightClickBlock with placer: pos=%s face=%s held=%s", event.getPos(), event.getFace(), held);
            if (!OreGeneratorManager.isAdmin(player.getUUID())) {
                DebugLog.info((ServerPlayer) player, "Denied: not in admin placer mode");
                player.sendSystemMessage(Component.literal("§cYou must be in admin mode to place generators."));
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
                event.setUseItem(Event.Result.DENY);
                event.setUseBlock(Event.Result.DENY);
                return;
            }

            var face = event.getFace();
            if (face == null) {
                DebugLog.info((ServerPlayer) player, "Denied: face is null");
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
                event.setUseItem(Event.Result.DENY);
                event.setUseBlock(Event.Result.DENY);
                return;
            }

            BlockPos targetPos = event.getPos().relative(face);
            ServerLevel level = (ServerLevel) player.level;
            DebugLog.info((ServerPlayer) player, "Placing generator at %s", targetPos);

            BlockState existing = level.getBlockState(targetPos);
            if (!existing.isAir() && !existing.getMaterial().isReplaceable()) {
                DebugLog.info((ServerPlayer) player, "Denied: target not replaceable, existing=%s", existing);
                player.sendSystemMessage(Component.literal("§cTarget block is not replaceable."));
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
                event.setUseItem(Event.Result.DENY);
                event.setUseBlock(Event.Result.DENY);
                return;
            }

            if (OreGeneratorManager.isGenerator(targetPos)) {
                DebugLog.info((ServerPlayer) player, "Denied: generator already exists at %s", targetPos);
                player.sendSystemMessage(Component.literal("§cA generator already exists here."));
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
                event.setUseItem(Event.Result.DENY);
                event.setUseBlock(Event.Result.DENY);
                return;
            }

            // Place bedrock in world so there is a solid target for mining
            level.setBlockAndUpdate(targetPos, Blocks.BEDROCK.defaultBlockState());
            DebugLog.info((ServerPlayer) player, "World set to bedrock at %s", targetPos);

            Block defaultOre = CavyOreConfig.getRandomOre();
            OreGeneratorManager.addGenerator(targetPos, defaultOre);
            DebugLog.info((ServerPlayer) player, "Generator registered at %s defaultOre=%s", targetPos, defaultOre);

            // Enqueue a visual update for next tick so our illusion overrides the vanilla bedrock update packet order
            synchronized (PENDING_PLACEMENT_VISUALS) {
                boolean added = PENDING_PLACEMENT_VISUALS.add(new PlacementKey(level.dimension(), targetPos));
                DebugLog.info((ServerPlayer) player, "Queued placement visual update at %s (added=%s)", targetPos, added);
            }

            // Particles for feedback now
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
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setUseItem(Event.Result.DENY);
            event.setUseBlock(Event.Result.DENY);
            event.setCanceled(true);
        }
    }

    // Tool checks
    private static boolean isInvalidToolForOre(BlockState oreState, ItemStack tool) {
        if (!oreState.is(BlockTags.MINEABLE_WITH_PICKAXE)) return true;
        return !(tool.getItem() instanceof PickaxeItem);
    }

    private static boolean isTierSufficient(BlockState oreState, ItemStack tool) {
        if (!(tool.getItem() instanceof PickaxeItem pick)) return false;
        int required = 0; // wood=0, stone=1, iron=2, diamond=3, netherite=4
        if (oreState.is(BlockTags.NEEDS_STONE_TOOL)) required = 1;
        if (oreState.is(BlockTags.NEEDS_IRON_TOOL)) required = 2;
        if (oreState.is(BlockTags.NEEDS_DIAMOND_TOOL)) required = 3;
        Tier tier = pick.getTier();
        int have = 0;
        if (tier == Tiers.STONE) have = 1;
        else if (tier == Tiers.IRON) have = 2;
        else if (tier == Tiers.DIAMOND) have = 3;
        else if (tier == Tiers.NETHERITE) have = 4;
        // wood/gold/others default 0
        return have >= required;
    }

    @SuppressWarnings("deprecation")
    private static long computeBreakTimeMs(ServerPlayer player, ServerLevel level, BlockPos pos, BlockState state, boolean tierOk) {
         float hardness = state.getDestroySpeed(level, pos);
         if (hardness < 0) return Long.MAX_VALUE; // unbreakable (bedrock, etc.)
         // Effective break speed (deprecated API still valid, suppress warning to keep compatibility)
         float speed = player.getDestroySpeed(state);
         if (speed <= 0) return Long.MAX_VALUE;
         double ticks = Math.ceil((hardness * 30.0) / speed) * (tierOk ? 1.0 : 5.0);
         long ms = (long) (ticks * 50.0);
         return Math.max(ms, 100L);
     }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        BlockPos pos = event.getPos();
        Level level = player.level;

        if (!level.isClientSide && OreGeneratorManager.isAdmin(player.getUUID())) {
            if (OreGeneratorManager.isGenerator(pos)) {
                DebugLog.info((ServerPlayer) player, "Removing generator at %s", pos);
                InstanceManager.clearAllAt(pos);
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                OreGeneratorManager.removeGenerator(pos);
                player.sendSystemMessage(Component.literal("§cGenerator at §e" + pos + " §cremoved."));
                int before = pendingRegens.size();
                pendingRegens.keySet().removeIf(k -> k.pos.equals(pos));
                DebugLog.info((ServerPlayer) player, "Removed pending regen tasks at %s (before=%d after=%d)", pos, before, pendingRegens.size());
                event.setCanceled(true);
            }
            return;
        }

        // Non-admin mining path (simulate mining with speed)
        if (!level.isClientSide && OreGeneratorManager.isGenerator(pos) && !OreGeneratorManager.isAdmin(player.getUUID())) {
            if (!(level instanceof ServerLevel serverLevel)) return;
            ServerPlayer sp = (ServerPlayer) player;
            DebugLog.info(sp, "LeftClickBlock at gen %s (simulate mining)", pos);

            // Cooldown check
            PlayerOreInstance instance = InstanceManager.getInstance(sp, pos);
            long now = System.currentTimeMillis();
            if (instance != null && now < instance.getRegenAtMillis()) {
                DebugLog.info(sp, "Break denied due to cooldown remain=%dms", instance.getRegenAtMillis() - now);
                player.sendSystemMessage(Component.literal("§cThis generator is on cooldown!"));
                event.setCanceled(true);
                return;
            }

            // Which ore is shown to this player
            Block oreBlock = (instance != null && instance.getOreBlock() != Blocks.BEDROCK)
                    ? instance.getOreBlock() : OreGeneratorManager.getOreFor(pos);
            BlockState state = oreBlock.defaultBlockState();

            // Tool rules
            ItemStack tool = player.getMainHandItem();
            if (!state.is(BlockTags.MINEABLE_WITH_PICKAXE) || !(tool.getItem() instanceof PickaxeItem)) {
                DebugLog.info(sp, "Break denied: invalid tool for %s using %s", oreBlock, tool);
                player.sendSystemMessage(Component.literal("§cYou need a pickaxe to mine this!"));
                event.setCanceled(true);
                return;
            }
            boolean tierOk = isTierSufficient(state, tool);

            // Manage or create mining session
            MiningSession sess = ACTIVE_MINES.get(sp.getUUID());
            if (sess != null && sess.pos.equals(pos) && ItemStack.isSameItemSameTags(sess.toolSnapshot, tool) && sess.state == state) {
                // continue mining
                sess.lastPulseAt = System.currentTimeMillis();
                sess.pulses++;
            } else {
                // reset previous session if different target/tool
                ACTIVE_MINES.remove(sp.getUUID());
                long start = System.currentTimeMillis();
                long duration = computeBreakTimeMs(sp, serverLevel, pos, state, tierOk);
                long finishAt = start + duration;
                sess = new MiningSession(sp.getUUID(), serverLevel, pos, state, tierOk, tool, start, finishAt);
                ACTIVE_MINES.put(sp.getUUID(), sess);
                DebugLog.info(sp, "Mining session started at %s: ore=%s tierOk=%s duration=%dms", pos, oreBlock, tierOk, duration);
            }

            // Prevent vanilla processing
            event.setCanceled(true);
        }
    }

    private static void completeMine(ServerPlayer sp, MiningSession sess) {
        BlockPos pos = sess.pos;
        ServerLevel serverLevel = sess.level;
        BlockState state = sess.state;
        Block oreBlock = state.getBlock();

        // Drops only if tier sufficient
        List<ItemStack> drops = sess.tierSufficient
                ? Block.getDrops(state, serverLevel, pos, null, sp, sp.getMainHandItem())
                : Collections.emptyList();
        DebugLog.info(sp, "Complete mining %s at %s drops=%d", oreBlock, pos, drops.size());

        boolean allAdded = true;
        for (ItemStack stack : drops) {
            ItemStack toGive = stack.copy();
            boolean added = sp.addItem(toGive);
            if (!added && !toGive.isEmpty()) {
                allAdded = false;
                sp.drop(toGive, false);
            }
        }
        if (!drops.isEmpty() && !allAdded) {
            sp.sendSystemMessage(Component.literal("§eSome items were dropped due to lack of inventory space."));
        }

        // Bedrock illusion + cooldown (only for this player)
        sp.connection.send(new ClientboundBlockUpdatePacket(pos, Blocks.BEDROCK.defaultBlockState()));
        serverLevel.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 15, 0.3, 0.3, 0.3, 0.01);
        serverLevel.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 5, 0.2, 0.2, 0.2, 0.01);

        long regenAt = System.currentTimeMillis() + COOLDOWN_TIME_MS;
        InstanceManager.setInstance(sp, pos, Blocks.BEDROCK, regenAt);
        RegenKey key = new RegenKey(sp.getUUID(), pos);
        pendingRegens.put(key, new RegenTask(serverLevel, pos, sp, regenAt));
        DebugLog.info(sp, "Regen scheduled at %d (in %dms) for %s", regenAt, COOLDOWN_TIME_MS, pos);
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
            DebugLog.info((ServerPlayer) player, "BreakEvent at gen %s", pos);
            event.setCanceled(true);

            ServerPlayer sp = (ServerPlayer) player;
            PlayerOreInstance instance = InstanceManager.getInstance(sp, pos);
            long now = System.currentTimeMillis();
            if (instance != null && now < instance.getRegenAtMillis()) {
                DebugLog.info(sp, "Break denied due to cooldown remain=%dms", instance.getRegenAtMillis() - now);
                player.sendSystemMessage(Component.literal("§cThis generator is on cooldown!"));
                return;
            }

            Block oreBlock = instance != null ? instance.getOreBlock() : OreGeneratorManager.getOreFor(pos);
            BlockState state = oreBlock.defaultBlockState();
            ItemStack tool = player.getMainHandItem();
            if (isInvalidToolForOre(state, tool)) {
                DebugLog.info(sp, "Break denied: invalid tool for %s using %s", oreBlock, tool);
                player.sendSystemMessage(Component.literal("§cYou need a pickaxe to mine this!"));
                return;
            }
            boolean tierOk = isTierSufficient(state, tool);

            // Start a mining session here as well to avoid instant break
            long nowMs = System.currentTimeMillis();
            long duration = computeBreakTimeMs(sp, serverLevel, pos, state, tierOk);
            ACTIVE_MINES.put(sp.getUUID(), new MiningSession(sp.getUUID(), serverLevel, pos, state, tierOk, tool, nowMs, nowMs + duration));
            DebugLog.info(sp, "BreakEvent -> started mining session dur=%dms at %s", duration, pos);
        }
    }

    private static final Map<RegenKey, RegenTask> pendingRegens = new HashMap<>();

    private record RegenTask(ServerLevel level, BlockPos pos, ServerPlayer player, long triggerTime) {}

    private record RegenKey(UUID playerId, BlockPos pos) {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        long now = System.currentTimeMillis();

        // Process any placement visuals that were queued this tick
        List<PlacementKey> toSend;
        synchronized (PENDING_PLACEMENT_VISUALS) {
            if (PENDING_PLACEMENT_VISUALS.isEmpty()) {
                toSend = null;
            } else {
                toSend = new ArrayList<>(PENDING_PLACEMENT_VISUALS);
                PENDING_PLACEMENT_VISUALS.clear();
            }
        }
        if (toSend != null) {
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                for (PlacementKey key : toSend) {
                    ServerLevel lvl = server.getLevel(key.dim);
                    if (lvl == null) continue;
                    BlockPos pos = key.pos;

                    // For each online player in this dimension, set instance + send the correct illusion
                    for (ServerPlayer sp : server.getPlayerList().getPlayers()) {
                        if (sp.level != lvl) continue;
                        Block oreForPlayer;
                        if (OreGeneratorManager.isAdmin(sp.getUUID())) {
                            oreForPlayer = OreGeneratorManager.getOreFor(pos); // show assigned ore to admins
                        } else {
                            oreForPlayer = CavyOreConfig.getRandomOre(); // per-player illusion
                        }
                        InstanceManager.setInstance(sp, pos, oreForPlayer, 0);
                        sp.connection.send(new ClientboundBlockUpdatePacket(pos, oreForPlayer.defaultBlockState()));
                        DebugLog.info(sp, "PostPlace visual at %s ore=%s (admin=%s)", pos, oreForPlayer, OreGeneratorManager.isAdmin(sp.getUUID()));
                    }
                }
            }
        }

        // Regen processing
        var regenIter = pendingRegens.entrySet().iterator();
        while (regenIter.hasNext()) {
            var entry = regenIter.next();
            RegenTask task = entry.getValue();
            if (now >= task.triggerTime) {
                Block newOre = CavyOreConfig.getRandomOre();
                InstanceManager.setInstance(task.player, task.pos, newOre, 0);
                task.player.connection.send(new ClientboundBlockUpdatePacket(task.pos, newOre.defaultBlockState()));
                DebugLog.info(task.player, "Regen fired at %s -> ore=%s", task.pos, newOre);
                regenIter.remove();
            }
        }
        // Mining sessions processing
        var mineIter = ACTIVE_MINES.entrySet().iterator();
        while (mineIter.hasNext()) {
            var e = mineIter.next();
            MiningSession sess = e.getValue();
            ServerPlayer sp = sess.level.getServer().getPlayerList().getPlayer(sess.playerId);
            if (sp == null) { mineIter.remove(); continue; }

            // Treat active arm swing as a mining pulse
            if (sp.swinging) {
                sess.lastPulseAt = now;
            }

            // Cancel if tool changed
            ItemStack current = sp.getMainHandItem();
            if (!ItemStack.isSameItemSameTags(current, sess.toolSnapshot)) { mineIter.remove(); continue; }

            // Cancel if player moved too far from target (>6 blocks)
            double dx = (sess.pos.getX() + 0.5) - sp.getX();
            double dy = (sess.pos.getY() + 0.5) - sp.getY();
            double dz = (sess.pos.getZ() + 0.5) - sp.getZ();
            if ((dx*dx + dy*dy + dz*dz) > 36.0) { mineIter.remove(); continue; }

            // Cancel if not looking at the target block anymore
            Vec3 eye = sp.getEyePosition(1.0f);
            Vec3 look = sp.getLookAngle();
            Vec3 reach = eye.add(look.scale(5.0));
            ClipContext ctx = new ClipContext(eye, reach, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, sp);
            BlockHitResult hit = sp.level.clip(ctx);
            if (!hit.getBlockPos().equals(sess.pos)) {
                mineIter.remove();
                continue;
            }

            // Cancel if idle (not recently swinging)
            if (now - sess.lastPulseAt > MINING_IDLE_TIMEOUT_MS) { mineIter.remove(); continue; }

            if (now >= sess.finishAt) {
                completeMine(sp, sess);
                mineIter.remove();
            }
        }
    }

    // Helper: send current visual state for all generators to a player
    public static void syncPlayerOres(ServerPlayer player) {
        DebugLog.info(player, "syncPlayerOres: gens=%d", OreGeneratorManager.getAllGeneratorPositions().size());
        for (BlockPos pos : OreGeneratorManager.getAllGeneratorPositions()) {
            PlayerOreInstance inst = InstanceManager.getInstance(player, pos);
            long now = System.currentTimeMillis();
            if (inst == null) {
                Block ore = OreGeneratorManager.getOreFor(pos);
                InstanceManager.setInstance(player, pos, ore, 0);
                player.connection.send(new ClientboundBlockUpdatePacket(pos, ore.defaultBlockState()));
                DebugLog.info(player, "sync: set new instance at %s ore=%s", pos, ore);
            } else {
                if (now < inst.getRegenAtMillis()) {
                    player.connection.send(new ClientboundBlockUpdatePacket(pos, Blocks.BEDROCK.defaultBlockState()));
                    DebugLog.info(player, "sync: cooldown bedrock at %s remain=%dms", pos, inst.getRegenAtMillis() - now);
                } else {
                    player.connection.send(new ClientboundBlockUpdatePacket(pos, inst.getOreBlock().defaultBlockState()));
                    DebugLog.info(player, "sync: show ore at %s ore=%s", pos, inst.getOreBlock());
                }
            }
        }
    }
}
