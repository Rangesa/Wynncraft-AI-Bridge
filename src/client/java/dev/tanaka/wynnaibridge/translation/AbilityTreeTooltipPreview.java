package dev.tanaka.wynnaibridge.translation;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Display-only semantic translation for Wynncraft Ability Tree item tooltips. */
public final class AbilityTreeTooltipPreview {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_LOGGED_SIGNATURES = 128;
    private static final Pattern ABILITY_TREE_MARKER = Pattern.compile(
        "(?i)(?:\\bTotal\\s+Damage\\s*:|\\bRequired\\s+Ability\\s*:|"
            + "\\bAbility\\s+Points\\s*:|\\bMin\\s+[A-Za-z][A-Za-z ]*\\s+Archetype\\s*:|"
            + "\\bYou\\s+do\\s+not\\s+meet\\s+the\\s+requirements\\b)"
    );
    private static final Set<String> LOGGED_SIGNATURES = new LinkedHashSet<>();

    private AbilityTreeTooltipPreview() {}

    public static PreviewResult interceptContainerTooltip(
        Minecraft minecraft,
        ItemStack stack,
        List<Component> originalLines,
        int x,
        int y
    ) {
        ServerData server = minecraft == null ? null : minecraft.getCurrentServer();
        String address = server == null ? null : server.ip;
        if (!isWynncraftHost(address) || stack == null || stack.isEmpty()
            || originalLines == null || originalLines.size() < 2 || !isAbilityTreeTooltip(originalLines)) {
            return new PreviewResult(false, originalLines, 0, 0);
        }

        String abilityTitle = cleanTitle(originalLines.getFirst());
        List<Component> displayLines = originalLines;
        int modifiedLines = 0;
        int removedFormattingCodes = 0;

        for (int row = 1; row < originalLines.size(); row++) {
            Component source = originalLines.get(row);
            if (source == null) continue;

            ComponentTransform result = translateComponent(source, abilityTitle);
            removedFormattingCodes += result.removedFormattingCodes();
            if (!result.changed()) continue;

            if (displayLines == originalLines) displayLines = new ArrayList<>(originalLines);
            displayLines.set(row, result.component());
            WynncraftTooltipPreview.rememberDisplayedTextForMcpCapture(
                result.component().getString(), source.getString()
            );
            modifiedLines++;
        }

        if (modifiedLines > 0) {
            WynncraftTooltipPreview.captureOriginalTooltipLinesForMcp(originalLines, x, y);
            displayLines = List.copyOf(displayLines);
        }

        logOnce(originalLines, modifiedLines, removedFormattingCodes);
        return new PreviewResult(true, displayLines, modifiedLines, removedFormattingCodes);
    }

    static boolean isAbilityTreeTooltip(List<Component> lines) {
        if (lines == null || lines.size() < 2) return false;
        for (int row = 1; row < lines.size(); row++) {
            Component line = lines.get(row);
            if (line == null) continue;
            String plain = stripLegacyCodes(line.getString());
            if (ABILITY_TREE_MARKER.matcher(plain).find()) return true;
        }
        return false;
    }

    static ComponentTransform translateComponent(Component source, String abilityTitle) {
        MutableComponent output;
        boolean changed = false;
        int removedFormattingCodes = 0;
        List<RunTrace> traces = new ArrayList<>();

        if (source.getContents() instanceof PlainTextContents plain) {
            output = Component.literal("").setStyle(source.getStyle());
            List<StyledText> styledRuns = parseLegacyFormatting(plain.text(), source.getStyle());
            for (StyledText run : styledRuns) {
                removedFormattingCodes += run.removedFormattingCodes();
                if (run.text().isEmpty()) continue;
                AbilityTreeTextPreprocessor.PreparedText prepared =
                    AbilityTreeTextPreprocessor.prepare(run.text(), abilityTitle);
                String displayedPlain;
                String cacheKey = "ability-tree-v2|" + abilityTitle + "|" + run.text();
                String cached = prepared.hasNaturalEnglish()
                    ? TranslationService.INSTANCE.findCachedOrQueue(
                        TranslationService.ABILITY_TREE_NAMESPACE, cacheKey, prepared.requestText()
                    )
                    : null;
                String displayedMasked = cached == null ? prepared.requestText() : cached;
                boolean translated = cached != null;

                try {
                    displayedPlain = prepared.restore(displayedMasked);
                    WynncraftTooltipPreview.appendPreparedRuns(
                        output, prepared, displayedMasked, run.style(), translated
                    );
                } catch (IllegalArgumentException invalidProtectedRun) {
                    // Keep the current source visible if a persisted/provider string ever loses a token.
                    WynncraftTooltipPreview.appendPreparedRuns(
                        output, prepared, prepared.requestText(), run.style(), false
                    );
                    displayedPlain = prepared.restore(prepared.requestText());
                    LOGGER.warn("[WAB Ability Tree] rejected translation with altered protected text");
                }

                changed |= !displayedPlain.equals(run.text());
                if (!displayedPlain.equals(run.text())) {
                    traces.add(new RunTrace(run.text(), prepared.requestText(), displayedPlain));
                }
            }
        } else {
            output = source.copy();
            output.getSiblings().clear();
        }

        List<Component> originalSiblings = source.getSiblings();
        for (Component sibling : originalSiblings) {
            ComponentTransform nested = translateComponent(sibling, abilityTitle);
            output.append(nested.component());
            changed |= nested.changed();
            removedFormattingCodes += nested.removedFormattingCodes();
        }
        changed |= removedFormattingCodes > 0 || !output.getString().equals(source.getString());
        for (RunTrace trace : traces) {
            WynncraftTooltipPreview.logComponentRebuild(
                "Ability Tree", source, trace.originalText(), trace.providerText(), trace.displayText(), output
            );
        }
        return new ComponentTransform(output, changed, removedFormattingCodes);
    }

    static List<StyledText> parseLegacyFormatting(String text, Style baseStyle) {
        List<StyledText> runs = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();
        Style currentStyle = baseStyle;
        int removedCodes = 0;

        for (int offset = 0; offset < text.length();) {
            if (text.charAt(offset) == ChatFormatting.PREFIX_CODE && offset + 1 < text.length()) {
                int hexEnd = parseHexFormatting(text, offset);
                if (hexEnd > offset) {
                    flushRun(runs, currentText, currentStyle, removedCodes);
                    removedCodes = 0;
                    int rgb = parseRgb(text, offset);
                    currentStyle = currentStyle.withColor(rgb);
                    offset = hexEnd;
                    removedCodes++;
                    continue;
                }

                ChatFormatting formatting = ChatFormatting.getByCode(text.charAt(offset + 1));
                if (formatting != null) {
                    flushRun(runs, currentText, currentStyle, removedCodes);
                    removedCodes = 0;
                    currentStyle = applyLegacyFormatting(currentStyle, formatting);
                    offset += 2;
                    removedCodes++;
                    continue;
                }
            }

            int codePoint = text.codePointAt(offset);
            currentText.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }

        flushRun(runs, currentText, currentStyle, removedCodes);
        return List.copyOf(runs);
    }

    private static Style applyLegacyFormatting(Style style, ChatFormatting formatting) {
        return switch (formatting) {
            case BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED, DARK_PURPLE, GOLD, GRAY,
                DARK_GRAY, BLUE, GREEN, AQUA, RED, LIGHT_PURPLE, YELLOW, WHITE -> style.withColor(formatting);
            case OBFUSCATED -> style.withObfuscated(true);
            case BOLD -> style.withBold(true);
            case STRIKETHROUGH -> style.withStrikethrough(true);
            case UNDERLINE -> style.withUnderlined(true);
            case ITALIC -> style.withItalic(true);
            case RESET -> resetLegacyStyle(style);
        };
    }

    private static Style resetLegacyStyle(Style style) {
        return style.withColor((TextColor) null)
            .withBold(false)
            .withItalic(false)
            .withUnderlined(false)
            .withStrikethrough(false)
            .withObfuscated(false);
    }

    private static void flushRun(List<StyledText> runs, StringBuilder text, Style style, int removedCodes) {
        if (text.isEmpty()) {
            if (removedCodes > 0) runs.add(new StyledText("", style, removedCodes));
            return;
        }
        runs.add(new StyledText(text.toString(), style, removedCodes));
        text.setLength(0);
    }

    private static int parseHexFormatting(String text, int start) {
        if (start + 13 >= text.length() || Character.toLowerCase(text.charAt(start + 1)) != 'x') return -1;
        for (int part = 0; part < 6; part++) {
            int sectionOffset = start + 2 + part * 2;
            if (text.charAt(sectionOffset) != ChatFormatting.PREFIX_CODE
                || Character.digit(text.charAt(sectionOffset + 1), 16) < 0) return -1;
        }
        return start + 14;
    }

    private static int parseRgb(String text, int start) {
        int rgb = 0;
        for (int part = 0; part < 6; part++) {
            int hexOffset = start + 3 + part * 2;
            rgb = (rgb << 4) | Character.digit(text.charAt(hexOffset), 16);
        }
        return rgb;
    }

    static String cleanTitle(Component title) {
        if (title == null) return "";
        StringBuilder result = new StringBuilder();
        for (StyledText run : parseLegacyFormatting(title.getString(), title.getStyle())) {
            run.text().codePoints().filter(codePoint -> {
                int type = Character.getType(codePoint);
                return codePoint < 0x80
                    && type != Character.PRIVATE_USE && type != Character.FORMAT && type != Character.CONTROL;
            }).forEach(result::appendCodePoint);
        }
        return result.toString().strip();
    }

    private static String stripLegacyCodes(String text) {
        StringBuilder result = new StringBuilder();
        for (StyledText run : parseLegacyFormatting(text, Style.EMPTY)) result.append(run.text());
        return result.toString();
    }

    private static boolean isWynncraftHost(String address) {
        if (address == null || address.isBlank()) return false;
        String host = address.strip().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int closingBracket = host.indexOf(']');
            if (closingBracket < 0) return false;
            host = host.substring(1, closingBracket);
        } else {
            int firstColon = host.indexOf(':');
            int lastColon = host.lastIndexOf(':');
            if (firstColon >= 0 && firstColon == lastColon) host = host.substring(0, firstColon);
        }
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.equals("wynncraft.com") || host.endsWith(".wynncraft.com");
    }

    private static void logOnce(List<Component> lines, int modifiedLines, int removedCodes) {
        List<String> plain = lines.stream().map(line -> line == null ? "" : line.getString()).toList();
        String signature = Integer.toHexString(plain.hashCode()) + '|' + modifiedLines + '|' + removedCodes;
        synchronized (LOGGED_SIGNATURES) {
            if (!LOGGED_SIGNATURES.add(signature)) return;
            while (LOGGED_SIGNATURES.size() > MAX_LOGGED_SIGNATURES) {
                Iterator<String> iterator = LOGGED_SIGNATURES.iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }
        LOGGER.info(
            "[WAB Ability Tree] intercepted lines={} modified={} formattingCodesRemoved={} wynncraft=true",
            lines.size(), modifiedLines, removedCodes
        );
    }

    public record PreviewResult(boolean handled, List<Component> lines, int modifiedLines, int formattingCodesRemoved) {}

    record StyledText(String text, Style style, int removedFormattingCodes) {}

    record ComponentTransform(Component component, boolean changed, int removedFormattingCodes) {}

    private record RunTrace(String originalText, String providerText, String displayText) {}
}
