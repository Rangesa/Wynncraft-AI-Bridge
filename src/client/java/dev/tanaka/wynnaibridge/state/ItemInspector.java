package dev.tanaka.wynnaibridge.state;

import dev.tanaka.wynnaibridge.capture.FormattedTextUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Converts an ItemStack into a JSON-friendly view made only from information
 * the local client can already display to the player.
 */
public final class ItemInspector {
    private ItemInspector() {}

    public static ItemView inspect(Minecraft minecraft, ItemStack stack, boolean includeTooltip, boolean advanced) {
        if (stack == null || stack.isEmpty()) return null;

        Component name = stack.getHoverName();
        List<TooltipLine> tooltip = List.of();
        if (includeTooltip && minecraft.level != null && minecraft.player != null) {
            TooltipFlag flag = advanced ? TooltipFlag.ADVANCED : TooltipFlag.NORMAL;
            List<Component> components = stack.getTooltipLines(
                Item.TooltipContext.of(minecraft.level),
                minecraft.player,
                flag
            );
            List<TooltipLine> lines = new ArrayList<>(components.size());
            for (Component component : components) {
                lines.add(new TooltipLine(
                    component == null ? "" : component.getString(),
                    FormattedTextUtil.firstColorHex(component)
                ));
            }
            tooltip = List.copyOf(lines);
        }

        return new ItemView(
            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
            name.getString(),
            FormattedTextUtil.firstColorHex(name),
            stack.getCount(),
            tooltip,
            componentsHash(stack)
        );
    }

    private static String componentsHash(ItemStack stack) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(stack.getComponents().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    public record TooltipLine(String text, String color) {}

    public record ItemView(
        String itemId,
        String name,
        String nameColor,
        int count,
        List<TooltipLine> tooltip,
        String componentsHash
    ) {}
}
