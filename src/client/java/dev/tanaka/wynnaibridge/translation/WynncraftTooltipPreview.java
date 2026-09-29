package dev.tanaka.wynnaibridge.translation;

import com.mojang.logging.LogUtils;
import dev.tanaka.wynnaibridge.capture.FormattedTextUtil;
import dev.tanaka.wynnaibridge.capture.TextCaptureStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Preview-only item lore replacement at the container-screen extraction boundary.
 * It never changes ItemStack data or the tooltip returned to MCP callers.
 */
public final class WynncraftTooltipPreview {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long CAPTURE_RESTORE_TTL_MS = 30_000L;
    private static final int MAX_CAPTURE_RESTORE_ENTRIES = 512;
    private static final int MAX_LOGGED_TOOLTIP_SIGNATURES = 256;
    private static final int MAX_LOGGED_REBUILD_SIGNATURES = 128;
    private static final String TOOLTIP_CACHE_VERSION = "tooltip-v2|";
    private static final Pattern COORDINATES = Pattern.compile(
        "(?i)^\\s*(?:x|y|z)?\\s*[-+]?\\d+(?:\\.\\d+)?\\s*[,/]\\s*[-+]?\\d+(?:\\.\\d+)?(?:\\s*[,/]\\s*[-+]?\\d+(?:\\.\\d+)?)?\\s*$"
    );
    private static final Pattern PLAYER_NAME_ONLY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{2,15}$");

    private static final Map<String, CaptureRestore> CAPTURE_RESTORES = new LinkedHashMap<>();
    private static final Set<String> LOGGED_TOOLTIP_SIGNATURES = new LinkedHashSet<>();
    private static final Set<String> LOGGED_REBUILD_SIGNATURES = new LinkedHashSet<>();

    private WynncraftTooltipPreview() {}

    public static PreviewResult interceptContainerTooltip(
        Minecraft minecraft,
        ItemStack stack,
        List<Component> originalLines,
        int x,
        int y
    ) {
        ServerData server = minecraft == null ? null : minecraft.getCurrentServer();
        String serverAddress = server == null ? null : server.ip;
        boolean wynncraftItem = isWynncraftItemTooltip(serverAddress, stack);
        List<Component> displayLines = originalLines;
        int modifiedLines = 0;
        String protectedTitle = originalLines == null || originalLines.isEmpty()
            ? ""
            : AbilityTreeTooltipPreview.cleanTitle(originalLines.getFirst());

        if (wynncraftItem && originalLines != null && !originalLines.isEmpty()) {
            for (int row = 1; row < originalLines.size(); row++) {
                Component original = originalLines.get(row);
                if (original == null || !isPreviewableLoreLine(original.getString())) continue;

                ComponentTransform transformed = translateComponent(original, protectedTitle);
                if (!transformed.changed()) continue;

                if (displayLines == originalLines) displayLines = new ArrayList<>(originalLines);
                displayLines.set(row, transformed.component());
                rememberForMcpCapture(transformed.component().getString(), original.getString());
                modifiedLines++;
            }
        }

        if (modifiedLines > 0) {
            captureOriginalLines(originalLines, x, y);
            displayLines = List.copyOf(displayLines);
        }

        logInterceptionOnce(serverAddress, stack, originalLines, wynncraftItem, modifiedLines);
        return new PreviewResult(displayLines, modifiedLines);
    }

    /**
     * Downstream visible-text capture sees display Components after extraction.
     * Restore only text that this preview actually emitted so MCP keeps source text.
     */
    public static String restoreForMcpCapture(String capturedText) {
        if (capturedText == null) return null;

        long now = System.currentTimeMillis();
        synchronized (CAPTURE_RESTORES) {
            pruneCaptureRestores(now);
            CaptureRestore exact = CAPTURE_RESTORES.get(capturedText);
            if (exact != null) return exact.originalText();
        }

        return capturedText;
    }

    /** Registers an Ability Tree display substitution in the same read-only MCP restore table. */
    public static void rememberDisplayedTextForMcpCapture(String previewText, String originalText) {
        rememberForMcpCapture(previewText, originalText);
    }

    /** Captures source tooltip rows before the Ability Tree display-only replacement is rendered. */
    public static void captureOriginalTooltipLinesForMcp(List<Component> lines, int x, int y) {
        captureOriginalLines(lines, x, y);
    }

    private static boolean isWynncraftItemTooltip(String serverAddress, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !isWynncraftHost(serverAddress)) return false;

        // Wynncraft items are server-provided custom names and/or custom lore.
        return stack.getCustomName() != null || stack.has(DataComponents.LORE);
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

    private static boolean isPreviewableLoreLine(String text) {
        if (text == null || text.isBlank() || COORDINATES.matcher(text).matches()
            || PLAYER_NAME_ONLY.matcher(text.strip()).matches()) return false;
        String stripped = text.stripLeading();
        if (stripped.startsWith("/") || stripped.contains("://")
            || stripped.regionMatches(true, 0, "www.", 0, 4)) {
            return false;
        }

        // Lines containing only numbers or custom glyph/control characters stay unchanged.
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if ((character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z')) return true;
        }
        return false;
    }

    /**
     * Translate literal text leaves independently so every leaf keeps its original Minecraft Style.
     * Non-text contents and glyph-only leaves are copied unchanged.
     */
    private static ComponentTransform translateComponent(Component source, String protectedTitle) {
        boolean changed = false;
        MutableComponent copy;
        List<RebuildTrace> traces = new ArrayList<>();

        if (source.getContents() instanceof PlainTextContents plainText) {
            String originalText = plainText.text();
            List<AbilityTreeTooltipPreview.StyledText> styledRuns =
                AbilityTreeTooltipPreview.parseLegacyFormatting(originalText, source.getStyle());
            int removedFormattingCodes = styledRuns.stream()
                .mapToInt(AbilityTreeTooltipPreview.StyledText::removedFormattingCodes).sum();
            copy = Component.literal("").setStyle(source.getStyle());

            for (AbilityTreeTooltipPreview.StyledText run : styledRuns) {
                if (run.text().isEmpty()) continue;

                AbilityTreeTextPreprocessor.PreparedText prepared =
                    AbilityTreeTextPreprocessor.prepare(run.text(), protectedTitle);
                String cacheKey = TOOLTIP_CACHE_VERSION + run.text();
                String cached = prepared.hasNaturalEnglish()
                    ? TooltipTranslationService.INSTANCE.findCachedOrQueue(cacheKey, prepared.requestText())
                    : null;
                boolean translated = cached != null;
                String displayMasked = translated ? cached : prepared.requestText();
                String displayText;
                try {
                    displayText = prepared.restore(displayMasked);
                } catch (IllegalArgumentException invalidProtectedRun) {
                    LOGGER.warn("[WAB Tooltip] rejected translation with altered protected text");
                    translated = false;
                    displayMasked = prepared.requestText();
                    displayText = prepared.restore(displayMasked);
                }

                String localPreview = prepared.restore(prepared.requestText());
                boolean localTermsChanged = !localPreview.equals(run.text());
                boolean runChanged = translated ? !displayText.equals(run.text()) : localTermsChanged;
                if (translated || localTermsChanged) {
                    appendPreparedRuns(copy, prepared, displayMasked, run.style(), translated);
                } else {
                    copy.append(Component.literal(run.text()).setStyle(run.style()));
                }
                changed |= runChanged;
                if (translated || localTermsChanged) {
                    traces.add(new RebuildTrace(run.text(), prepared.requestText(), displayText));
                }
            }
            changed |= removedFormattingCodes > 0;
        } else {
            copy = source.copy();
            copy.getSiblings().clear();
        }

        List<Component> originalSiblings = source.getSiblings();
        for (Component sibling : originalSiblings) {
            ComponentTransform transformedSibling = translateComponent(sibling, protectedTitle);
            copy.append(transformedSibling.component());
            changed |= transformedSibling.changed();
        }
        for (RebuildTrace trace : traces) {
            logComponentRebuild("Tooltip", source, trace.originalText(), trace.providerText(), trace.displayText(), copy);
        }
        return new ComponentTransform(copy, changed);
    }

    static void logComponentRebuild(
        String pipeline,
        Component source,
        String originalPlainText,
        String providerText,
        String translatedText,
        Component rebuilt
    ) {
        List<String> originalSiblings = describeSiblings(source.getSiblings());
        List<String> siblings = new ArrayList<>();
        List<Component> children = rebuilt.getSiblings();
        for (int index = 0; index < children.size(); index++) {
            Component sibling = children.get(index);
            Style style = sibling.getStyle();
            siblings.add("[" + index + "] text=" + quoteForLog(sibling.getString())
                + " style=" + describeStyle(style)
                + " glyphs=" + describeGlyphCodePoints(sibling.getString()));
        }

        String rebuiltPlainText = rebuilt.getString();
        String signature = pipeline + '\u0000' + originalPlainText + '\u0000' + providerText + '\u0000'
            + translatedText + '\u0000' + rebuiltPlainText + '\u0000'
            + originalSiblings + '\u0000' + siblings;
        synchronized (LOGGED_REBUILD_SIGNATURES) {
            if (!LOGGED_REBUILD_SIGNATURES.add(signature)) return;
            while (LOGGED_REBUILD_SIGNATURES.size() > MAX_LOGGED_REBUILD_SIGNATURES) {
                Iterator<String> iterator = LOGGED_REBUILD_SIGNATURES.iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }

        LOGGER.info(
            "[WAB {} Debug] original plain text={} source component text={} source style={} glyph codepoints={} provider input={} translated text={} rebuilt plain text={} original siblingCount={} original siblings={} rebuilt siblingCount={} rebuilt siblings={}",
            pipeline,
            quoteForLog(originalPlainText),
            quoteForLog(source.getString()),
            describeStyle(source.getStyle()),
            describeGlyphCodePoints(source.getString()),
            quoteForLog(providerText),
            quoteForLog(translatedText),
            quoteForLog(rebuiltPlainText),
            originalSiblings.size(),
            originalSiblings,
            children.size(),
            siblings
        );
    }

    private static List<String> describeSiblings(List<Component> siblings) {
        List<String> descriptions = new ArrayList<>();
        for (int index = 0; index < siblings.size(); index++) {
            Component sibling = siblings.get(index);
            Style style = sibling.getStyle();
            descriptions.add("[" + index + "] text=" + quoteForLog(sibling.getString())
                + " style=" + describeStyle(style)
                + " glyphs=" + describeGlyphCodePoints(sibling.getString()));
        }
        return descriptions;
    }

    private static String describeStyle(Style style) {
        return "font=" + style.getFont() + " color=" + style.getColor()
            + " bold=" + style.isBold() + " italic=" + style.isItalic()
            + " underline=" + style.isUnderlined() + " strike=" + style.isStrikethrough()
            + " obfuscated=" + style.isObfuscated();
    }

    private static String describeGlyphCodePoints(String text) {
        List<String> glyphs = new ArrayList<>();
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int type = Character.getType(codePoint);
            boolean extendedLatinGlyph = codePoint > 0x7F && Character.isLetter(codePoint)
                && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
            if (type == Character.PRIVATE_USE || type == Character.FORMAT || type == Character.CONTROL
                || type == Character.MATH_SYMBOL || type == Character.CURRENCY_SYMBOL
                || type == Character.MODIFIER_SYMBOL || type == Character.OTHER_SYMBOL || extendedLatinGlyph) {
                glyphs.add(String.format(Locale.ROOT, "U+%04X", codePoint));
            }
            offset += Character.charCount(codePoint);
        }
        return glyphs.toString();
    }

    private static String quoteForLog(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    /**
     * Wynncraft applies custom fonts to some lore Components. Keep glyphs and numeric runs in
     * that font, but explicitly render ordinary translated text with Minecraft's default font.
     */
    private static void appendFontSafeRuns(MutableComponent target, String text, Style originalStyle) {
        if (text.isEmpty()) return;

        int runStart = 0;
        int firstCodePoint = text.codePointAt(0);
        boolean preserveOriginalFont = preserveOriginalFont(firstCodePoint);
        for (int offset = Character.charCount(firstCodePoint); offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            boolean nextPreserveOriginalFont = preserveOriginalFont(codePoint);
            if (nextPreserveOriginalFont != preserveOriginalFont) {
                appendStyledRun(target, text.substring(runStart, offset), originalStyle, preserveOriginalFont);
                runStart = offset;
                preserveOriginalFont = nextPreserveOriginalFont;
            }
            offset += Character.charCount(codePoint);
        }
        appendStyledRun(target, text.substring(runStart), originalStyle, preserveOriginalFont);
    }

    private static void appendStyledRun(
        MutableComponent target,
        String text,
        Style originalStyle,
        boolean preserveOriginalFont
    ) {
        if (text.isEmpty()) return;
        Style runStyle = preserveOriginalFont
            ? originalStyle
            : originalStyle.withFont(FontDescription.DEFAULT);
        target.append(Component.literal(text).setStyle(runStyle));
    }

    private static boolean preserveOriginalFont(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.PRIVATE_USE
            || (codePoint > 0x7F && Character.isLetter(codePoint)
                && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN)
            || type == Character.FORMAT
            || type == Character.CONTROL
            || type == Character.MATH_SYMBOL
            || type == Character.CURRENCY_SYMBOL
            || type == Character.MODIFIER_SYMBOL
            || type == Character.OTHER_SYMBOL
            || Character.isDigit(codePoint)
            || codePoint == '+'
            || codePoint == '-'
            || codePoint == '\u2212'
            || codePoint == '%'
            || codePoint == ':'
            || codePoint == '.'
            || codePoint == ','
            || codePoint == '/';
    }

    static void appendPreparedRuns(
        MutableComponent target,
        AbilityTreeTextPreprocessor.PreparedText prepared,
        String translatedMasked,
        Style sourceStyle,
        boolean translated
    ) {
        Map<String, AbilityTreeTextPreprocessor.Replacement> replacements = prepared.replacements();
        for (String marker : replacements.keySet()) {
            int first = translatedMasked.indexOf(marker);
            if (first < 0 || translatedMasked.indexOf(marker, first + marker.length()) >= 0) {
                throw new IllegalArgumentException("Wynncraft translation altered a protected run");
            }
        }

        Set<String> foundMarkers = new LinkedHashSet<>();
        int cursor = 0;

        while (cursor < translatedMasked.length()) {
            int next = translatedMasked.length();
            String nextMarker = null;
            for (String marker : replacements.keySet()) {
                int markerIndex = translatedMasked.indexOf(marker, cursor);
                if (markerIndex >= 0 && markerIndex < next) {
                    next = markerIndex;
                    nextMarker = marker;
                }
            }

            if (next > cursor) {
                Style proseStyle = translated ? sourceStyle.withFont(FontDescription.DEFAULT) : sourceStyle;
                target.append(Component.literal(translatedMasked.substring(cursor, next)).setStyle(proseStyle));
                cursor = next;
            }
            if (nextMarker == null) break;

            AbilityTreeTextPreprocessor.Replacement replacement = replacements.get(nextMarker);
            foundMarkers.add(nextMarker);
            Style replacementStyle = replacement.kind() == AbilityTreeTextPreprocessor.RunKind.FIXED_JAPANESE
                ? sourceStyle.withFont(FontDescription.DEFAULT)
                : sourceStyle;
            target.append(Component.literal(replacement.text()).setStyle(replacementStyle));
            cursor = next + nextMarker.length();
        }

        if (foundMarkers.size() != replacements.size()) {
            throw new IllegalArgumentException("Wynncraft translation lost a protected run");
        }
    }

    private static void captureOriginalLines(List<Component> lines, int x, int y) {
        int row = 0;
        for (Component line : lines) {
            TextCaptureStore.INSTANCE.record(
                "tooltip.component",
                line == null ? "" : line.getString(),
                x,
                y + row++ * 10,
                FormattedTextUtil.firstColorHex(line)
            );
        }
    }

    private static void logInterceptionOnce(
        String serverAddress,
        ItemStack stack,
        List<Component> lines,
        boolean wynncraftItem,
        int modifiedLines
    ) {
        int lineCount = lines == null ? 0 : lines.size();
        String itemId = stack == null
            ? "<none>"
            : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        List<String> textSignature = lines == null
            ? List.of()
            : lines.stream().map(line -> line == null ? "" : line.getString()).toList();
        String signature = itemId + '|' + String.valueOf(serverAddress) + '|'
            + (stack == null ? 0 : stack.getCount()) + '|'
            + Integer.toHexString(textSignature.hashCode()) + '|'
            + wynncraftItem + '|' + modifiedLines;

        synchronized (LOGGED_TOOLTIP_SIGNATURES) {
            if (!LOGGED_TOOLTIP_SIGNATURES.add(signature)) return;
            while (LOGGED_TOOLTIP_SIGNATURES.size() > MAX_LOGGED_TOOLTIP_SIGNATURES) {
                Iterator<String> iterator = LOGGED_TOOLTIP_SIGNATURES.iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }

        LOGGER.info(
            "[WAB Tooltip] intercepted stack={} lines={} modified={} wynncraft={}",
            itemId,
            lineCount,
            modifiedLines,
            wynncraftItem
        );
    }

    private static void rememberForMcpCapture(String previewText, String originalText) {
        long now = System.currentTimeMillis();
        synchronized (CAPTURE_RESTORES) {
            pruneCaptureRestores(now);
            CAPTURE_RESTORES.put(previewText, new CaptureRestore(originalText, now));
            while (CAPTURE_RESTORES.size() > MAX_CAPTURE_RESTORE_ENTRIES) {
                Iterator<String> iterator = CAPTURE_RESTORES.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }
    }

    private static void pruneCaptureRestores(long now) {
        CAPTURE_RESTORES.entrySet().removeIf(entry ->
            now - entry.getValue().createdAt() > CAPTURE_RESTORE_TTL_MS
        );
    }

    public record PreviewResult(List<Component> lines, int modifiedLines) {}

    private record ComponentTransform(Component component, boolean changed) {}

    private record RebuildTrace(String originalText, String providerText, String displayText) {}

    private record CaptureRestore(String originalText, long createdAt) {}
}
