package com.alphine.cavywavy;

import com.alphine.cavywavy.commands.CommandCavyAdmin;
import com.alphine.cavywavy.config.CavyOreConfig;
import com.alphine.cavywavy.handlers.ForgeEventHandlers;
import com.alphine.cavywavy.instancing.InstanceManager;
import com.alphine.cavywavy.network.CavyNetworkHandler;
import com.alphine.cavywavy.util.OreGeneratorManager;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Main mod entry point
 */
@Mod(Cavywavy.MODID)
public class Cavywavy {

    public static final String MODID = "cavywavy";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Cavywavy() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Setup network + config
        modEventBus.addListener(this::commonSetup);

        // Register to Forge event bus
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[Cavywavy] Initializing core systems...");

        // Load ore config
        CavyOreConfig.load();

        // Register packets
        CavyNetworkHandler.register();

        // Initialize instancing manager (tick/event listener)
        MinecraftForge.EVENT_BUS.register(new ForgeEventHandlers()); // Forgot to register this last time so just placed it here because I was lazy...
        InstanceManager.init();
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        OreGeneratorManager.load();
        LOGGER.info("[Cavywavy] Server starting — Ready for block placement.");
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        OreGeneratorManager.saveNowIfNeeded();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandCavyAdmin.register(event.getDispatcher());
    }
}
