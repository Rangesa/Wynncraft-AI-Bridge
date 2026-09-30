package dev.tanaka.wynnaibridge;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screens.Screen;

/** Optional Mod Menu entrypoint; Mod Menu itself is not bundled with this mod. */
public final class WynnAiBridgeModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return WynnAiBridgeSettingsScreen::new;
    }
}
