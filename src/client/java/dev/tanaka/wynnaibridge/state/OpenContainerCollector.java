package dev.tanaka.wynnaibridge.state;

import dev.tanaka.wynnaibridge.capture.FormattedTextUtil;
import dev.tanaka.wynnaibridge.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of the container UI that is actually open on the player's screen.
 * This is intentionally visibility-scoped: a closed chest/ender chest is not
 * queried from world/server state.
 */
public final class OpenContainerCollector {
    private OpenContainerCollector() {}

    public static Result collect(Minecraft minecraft, boolean includeEmpty, boolean includeTooltips, boolean advanced) {
        if (minecraft.player == null || minecraft.level == null) {
            return Result.error("Not in a world");
        }
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            return Result.error("No container screen is open");
        }

        long stateRevision = UiStateRevisionTracker.INSTANCE.refresh(minecraft);

        var menu = screen.getMenu();
        Component title = screen.getTitle();
        Slot hovered = ((AbstractContainerScreenAccessor) screen).wynnAiBridge$getHoveredSlot();
        int hoveredMenuSlot = hovered == null ? -1 : menu.slots.indexOf(hovered);

        List<SlotView> slots = new ArrayList<>();
        int nonEmpty = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            boolean empty = stack.isEmpty();
            if (!empty) nonEmpty++;
            if (empty && !includeEmpty) continue;

            String section = slot.container == minecraft.player.getInventory() ? "PLAYER_INVENTORY" : "OPEN_CONTAINER";
            slots.add(new SlotView(
                i,
                slot.getContainerSlot(),
                section,
                i == hoveredMenuSlot,
                slot.isActive(),
                empty ? null : ItemInspector.inspect(minecraft, stack, includeTooltips, advanced)
            ));
        }

        ItemStack carried = menu.getCarried();
        return new Result(
            true,
            "",
            System.currentTimeMillis(),
            screen.getClass().getName(),
            menu.getClass().getName(),
            menu.containerId,
            menu.getStateId(),
            stateRevision,
            stateRevision,
            title.getString(),
            FormattedTextUtil.firstColorHex(title),
            menu.slots.size(),
            nonEmpty,
            hoveredMenuSlot >= 0 ? hoveredMenuSlot : null,
            carried == null || carried.isEmpty() ? null : ItemInspector.inspect(minecraft, carried, includeTooltips, advanced),
            List.copyOf(slots)
        );
    }

    public record SlotView(
        int menuSlot,
        int containerSlot,
        String section,
        boolean hovered,
        boolean active,
        ItemInspector.ItemView item
    ) {}

    public record Result(
        boolean ok,
        String error,
        long capturedAt,
        String screenClass,
        String menuClass,
        Integer containerId,
        Integer stateId,
        long screenRevision,
        long stateRevision,
        String title,
        String titleColor,
        Integer totalSlots,
        Integer nonEmptySlots,
        Integer hoveredMenuSlot,
        ItemInspector.ItemView carriedItem,
        List<SlotView> slots
    ) {
        static Result error(String error) {
            return new Result(false, error, System.currentTimeMillis(), "", "", null, null,
                0L, 0L, "", null, null, null, null, null, List.of());
        }
    }
}
