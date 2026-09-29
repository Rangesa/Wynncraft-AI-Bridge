package dev.tanaka.wynnaibridge;

import net.fabricmc.loader.api.FabricLoader;

public final class ModVersion {
    private static final String MOD_ID = "wynn_ai_bridge";

    private ModVersion() {}

    public static String get() {
        return FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }
}
