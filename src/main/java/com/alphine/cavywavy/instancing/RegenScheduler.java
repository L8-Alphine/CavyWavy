package com.alphine.cavywavy.instancing;

/**
 * Legacy regen scheduler disabled. Per-player regen is now handled in ForgeEventHandlers.
 * This class remains only to expose the cooldown constant for any older references.
 */
public class RegenScheduler {

    // Cooldown time in milliseconds (30 seconds)
    public static final long COOLDOWN_TIME_MS = 30_000L;
}
