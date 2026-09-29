package dev.tanaka.wynnaibridge.capture;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.concurrent.atomic.AtomicReference;

public final class FormattedTextUtil {
    private FormattedTextUtil() {}

    public static String toPlain(FormattedCharSequence sequence) {
        if (sequence == null) return "";
        StringBuilder builder = new StringBuilder();
        sequence.accept((index, style, codePoint) -> {
            builder.appendCodePoint(codePoint);
            return true;
        });
        return builder.toString();
    }

    public static String firstColorHex(FormattedCharSequence sequence) {
        if (sequence == null) return null;
        AtomicReference<String> found = new AtomicReference<>();
        sequence.accept((index, style, codePoint) -> {
            TextColor color = style.getColor();
            if (color != null) {
                found.compareAndSet(null, rgbHex(color.getValue()));
            }
            return found.get() == null;
        });
        return found.get();
    }

    public static String firstColorHex(Component component) {
        if (component == null) return null;
        String root = colorHex(component.getStyle().getColor());
        if (root != null) return root;
        return firstColorHex(component.getVisualOrderText());
    }

    public static String colorHex(TextColor color) {
        return color == null ? null : rgbHex(color.getValue());
    }

    public static String argbHex(int color) {
        return String.format("#%08X", color);
    }

    private static String rgbHex(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }
}
