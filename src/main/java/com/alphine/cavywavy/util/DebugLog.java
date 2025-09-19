package com.alphine.cavywavy.util;

import com.alphine.cavywavy.Cavywavy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Toggleable debug logger. Use DebugLog.info(...) in hot paths.
 */
public final class DebugLog {
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);

    private DebugLog() {}

    public static boolean isEnabled() { return ENABLED.get(); }
    public static void setEnabled(boolean enable) { ENABLED.set(enable); Cavywavy.LOGGER.info(prefix() + "debug=" + enable); }
    public static boolean toggle() {
        boolean newVal = !ENABLED.get();
        ENABLED.set(newVal);
        Cavywavy.LOGGER.info(prefix() + "debug toggled -> " + newVal);
        return newVal;
    }

    public static void info(String msg) {
        if (!ENABLED.get()) return;
        Cavywavy.LOGGER.info(prefix() + msg);
    }

    public static void info(String fmt, Object... args) {
        if (!ENABLED.get()) return;
        Cavywavy.LOGGER.info(prefix() + String.format(fmt, args));
    }

    public static void info(ServerPlayer player, String fmt, Object... args) {
        if (!ENABLED.get()) return;
        String name = player != null ? player.getGameProfile().getName() : "<null>";
        Cavywavy.LOGGER.info(prefix() + "[player=" + name + "] " + String.format(fmt, args));
    }

    public static void infoPos(BlockPos pos, String fmt, Object... args) {
        if (!ENABLED.get()) return;
        Cavywavy.LOGGER.info(prefix() + "[pos=" + pos + "] " + String.format(fmt, args));
    }

    private static String prefix() {
        return "[CavyDebug " + Instant.now().toEpochMilli() + "] ";
    }
}

