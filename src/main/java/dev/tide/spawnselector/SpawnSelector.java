package dev.tide.spawnselector;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.DistExecutor;
import org.slf4j.Logger;

@Mod(SpawnSelector.ID)
public final class SpawnSelector {
    public static final String ID = "spawnselector";
    public static final Logger LOG = LogUtils.getLogger();
    public SpawnSelector() {
        Packets.register();
        DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> ClientSetup::registerConfig);
        MinecraftForge.EVENT_BUS.register(new SelectionService());
        MinecraftForge.EVENT_BUS.register(new SpawnSelectorManager());
        MinecraftForge.EVENT_BUS.register(new SpawnSelectorDimensions());
        MinecraftForge.EVENT_BUS.addListener(SpawnEntries::reloadListener);
        MinecraftForge.EVENT_BUS.addListener(SpawnUiConfig::reloadListener);
    }
    // Keep client-only lambda signatures off the common entry class. Merely reading
    // LOG may verify its methods before DistExecutor is ever called on a server.
    private static final class ClientSetup {
        private static void registerConfig() {
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new SpawnConfigScreen(parent)));
        }
    }
}
