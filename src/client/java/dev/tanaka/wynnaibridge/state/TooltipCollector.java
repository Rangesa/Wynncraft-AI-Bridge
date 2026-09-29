package dev.tanaka.wynnaibridge.state;

import dev.tanaka.wynnaibridge.capture.FormattedTextUtil;
import dev.tanaka.wynnaibridge.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;

public final class TooltipCollector {
    private TooltipCollector() {}

    public static Result collect(Minecraft minecraft, Integer requestedMenuSlot, boolean advanced) {
        if (minecraft.level == null || minecraft.player == null) {
            return Result.error("Not in a world");
        }
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> containerScreen)) {
            return Result.error("No container screen is open");
        }

        var menu = containerScreen.getMenu();
        int menuSlot;
        if (requestedMenuSlot == null) {
            Slot hovered = ((AbstractContainerScreenAccessor) containerScreen).wynnAiBridge$getHoveredSlot();
            if (hovered == null) return Result.error("No slot is currently hovered");
            menuSlot = menu.slots.indexOf(hovered);
            if (menuSlot < 0) return Result.error("Hovered slot is not part of the current menu");
        } else {
            menuSlot = requestedMenuSlot;
        }

        if (menuSlot < 0 || menuSlot >= menu.slots.size()) {
            return Result.error("Slot out of range");
        }

        Slot slot = menu.slots.get(menuSlot);
        ItemStack stack = slot.getItem();
        if (stack.isEmpty()) return Result.error("Slot is empty");

        TooltipFlag flag = advanced ? TooltipFlag.ADVANCED : TooltipFlag.NORMAL;
        List<Component> components = stack.getTooltipLines(
            Item.TooltipContext.of(minecraft.level),
            minecraft.player,
            flag
        );

        List<Line> lines = new ArrayList<>(components.size());
        for (Component component : components) {
            lines.add(new Line(component.getString(), FormattedTextUtil.firstColorHex(component)));
        }

        Component name = stack.getHoverName();
        return new Result(
            true,
            "",
            menuSlot,
            slot.getContainerSlot(),
            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
            name.getString(),
            FormattedTextUtil.firstColorHex(name),
            stack.getCount(),
            List.copyOf(lines)
        );
    }

    public record Line(String text, String color) {}

    public record Result(
        boolean ok,
        String error,
        Integer menuSlot,
        Integer containerSlot,
        String itemId,
        String name,
        String nameColor,
        Integer count,
        List<Line> lines
    ) {
        static Result error(String error) {
            return new Result(false, error, null, null, "", "", null, null, List.of());
        }
    }
}
