/* Compile-only signature shim for the optional Mod Menu API. It is excluded from the mod JAR. */
package com.terraformersmc.modmenu.api;

import net.minecraft.client.gui.screens.Screen;

@FunctionalInterface
public interface ConfigScreenFactory<S extends Screen> {
    S create(Screen parent);
}
