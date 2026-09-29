package dev.tanaka.wynnaibridge.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Tracks meaningful client-visible inventory and screen changes for stale-state checks. */
public final class UiStateRevisionTracker {
    public static final UiStateRevisionTracker INSTANCE = new UiStateRevisionTracker();

    private String fingerprint;
    private long revision;

    private UiStateRevisionTracker() {}

    public long refresh(Minecraft minecraft) {
        String next = fingerprint(minecraft);
        if (fingerprint == null || !fingerprint.equals(next)) {
            fingerprint = next;
            revision++;
        }
        return revision;
    }

    private static String fingerprint(Minecraft minecraft) {
        StringBuilder out = new StringBuilder(2048);
        var world = minecraft.level;
        var player = minecraft.player;
        var screen = minecraft.gui.screen();
        out.append(System.identityHashCode(world)).append('|');
        out.append(System.identityHashCode(player)).append('|');
        out.append(System.identityHashCode(screen)).append('|');
        if (screen != null) out.append(screen.getClass().getName());
        out.append('|');

        AbstractContainerMenu menu = null;
        if (screen instanceof AbstractContainerScreen<?> containerScreen) {
            menu = containerScreen.getMenu();
            out.append(containerScreen.getTitle().getString());
        } else if (player != null) {
            menu = player.containerMenu;
        }
        if (menu != null) {
            out.append('|').append(menu.getClass().getName())
                .append('|').append(menu.containerId)
                .append('|').append(menu.getStateId());
            for (int i = 0; i < menu.slots.size(); i++) {
                out.append('|').append(i).append(':');
                appendStack(out, menu.slots.get(i).getItem());
            }
            out.append("|cursor:");
            appendStack(out, menu.getCarried());
        }

        if (player != null) {
            var inventory = player.getInventory();
            for (int i = 0; i < inventory.getNonEquipmentItems().size(); i++) {
                out.append("|inventory:").append(i).append(':');
                appendStack(out, inventory.getNonEquipmentItems().get(i));
            }
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                out.append("|equipment:").append(slot.name()).append(':');
                appendStack(out, player.getItemBySlot(slot));
            }
        }
        return out.toString();
    }

    private static void appendStack(StringBuilder out, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            out.append("empty");
            return;
        }
        out.append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
            .append('#').append(stack.getCount())
            .append('#').append(stack.getComponents());
    }
}
