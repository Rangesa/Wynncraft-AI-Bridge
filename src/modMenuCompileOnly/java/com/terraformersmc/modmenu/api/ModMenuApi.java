/* Compile-only signature shim for the optional Mod Menu API. It is excluded from the mod JAR. */
package com.terraformersmc.modmenu.api;

public interface ModMenuApi {
    default ConfigScreenFactory<?> getModConfigScreenFactory() { return null; }
}
