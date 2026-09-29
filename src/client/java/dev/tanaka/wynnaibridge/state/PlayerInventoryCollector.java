package dev.tanaka.wynnaibridge.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads only the local player's own inventory/equipment, i.e. state the player
 * can inspect by opening their inventory screen.
 */
public final class PlayerInventoryCollector {
    private PlayerInventoryCollector() {}

    public static Result collect(Minecraft minecraft, boolean includeEmpty, boolean includeTooltips, boolean advanced) {
        if (minecraft.player == null || minecraft.level == null) {
            return Result.error("Not in a world");
        }

        var inventory = minecraft.player.getInventory();
        long stateRevision = UiStateRevisionTracker.INSTANCE.refresh(minecraft);
        var currentScreen = minecraft.gui.screen();
        String screenClass = currentScreen == null ? null : currentScreen.getClass().getName();
        Integer containerSyncId = currentScreen instanceof AbstractContainerScreen<?> containerScreen
            ? containerScreen.getMenu().containerId
            : null;
        String screenTitle = currentScreen instanceof AbstractContainerScreen<?> containerScreen
            ? containerScreen.getTitle().getString()
            : null;
        var items = inventory.getNonEquipmentItems();
        int selected = inventory.getSelectedSlot();
        List<InventorySlot> inventorySlots = new ArrayList<>();

        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty() && !includeEmpty) continue;
            inventorySlots.add(new InventorySlot(
                i,
                i < 9 ? "HOTBAR" : "MAIN",
                i == selected,
                stack.isEmpty() ? null : ItemInspector.inspect(minecraft, stack, includeTooltips, advanced)
            ));
        }

        List<EquipmentItem> equipment = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = minecraft.player.getItemBySlot(slot);
            if (stack.isEmpty() && !includeEmpty) continue;
            equipment.add(new EquipmentItem(
                slot.name(),
                stack.isEmpty() ? null : ItemInspector.inspect(minecraft, stack, includeTooltips, advanced)
            ));
        }

        return new Result(
            true,
            "",
            System.currentTimeMillis(),
            stateRevision,
            stateRevision,
            screenClass,
            containerSyncId,
            screenTitle,
            selected,
            List.copyOf(inventorySlots),
            List.copyOf(equipment)
        );
    }

    public record InventorySlot(int inventorySlot, String section, boolean selected, ItemInspector.ItemView item) {}
    public record EquipmentItem(String equipmentSlot, ItemInspector.ItemView item) {}

    public record Result(
        boolean ok,
        String error,
        long capturedAt,
        long screenRevision,
        long stateRevision,
        String screenClass,
        Integer containerSyncId,
        String screenTitle,
        Integer selectedHotbarSlot,
        List<InventorySlot> inventory,
        List<EquipmentItem> equipment
    ) {
        static Result error(String error) {
            return new Result(false, error, System.currentTimeMillis(), 0L, 0L, null, null, null, null,
                List.of(), List.of());
        }
    }
}
