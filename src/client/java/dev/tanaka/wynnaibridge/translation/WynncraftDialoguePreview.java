package dev.tanaka.wynnaibridge.translation;

import com.mojang.logging.LogUtils;
import dev.tanaka.wynnaibridge.capture.MessageStore;
import dev.tanaka.wynnaibridge.capture.WynnTextSemantics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Display-only translation for completed Wynncraft dialogue in the HUD overlay. */
public final class WynncraftDialoguePreview {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_LOGGED_DIALOGUES = 256;
    private static final Set<String> LOGGED_DIALOGUES = new LinkedHashSet<>();

    private WynncraftDialoguePreview() {}

    public static Component translateCompletedDialogue(Component original) {
        if (original == null || !isWynncraftServer(Minecraft.getInstance())) return original;

        String rawText = original.getString();
        if (rawText == null || rawText.isBlank() || MessageStore.INSTANCE.isChatEcho(rawText)) return original;

        // Source metadata is informational only: the existing semantic parser decides whether this is a complete NPC dialogue.
        WynnTextSemantics.Dialogue dialogue = WynnTextSemantics.parseDialogue(rawText, "rendered:hud-overlay");
        if (dialogue == null || !dialogue.canContinue()) return original;

        ReplacementRange range = findReplacementRange(original, dialogue.text());
        if (range == null) return original;

        String translatedText = TranslationService.INSTANCE.findCachedOrQueue(
            TranslationService.DIALOGUE_NAMESPACE,
            dialogue.text()
        );
        if (translatedText == null || translatedText.isBlank() || translatedText.equals(dialogue.text())) return original;

        boolean[] inserted = {false};
        Cursor cursor = new Cursor();
        MutableComponent rebuilt = rebuild(
            original,
            cursor,
            range.start(),
            range.end(),
            translatedText,
            inserted
        );
        String expected = range.rawText().substring(0, range.start())
            + translatedText
            + range.rawText().substring(range.end());
        if (!inserted[0] || !rebuilt.getString().equals(expected)) return original;

        logDisplayedOnce(dialogue.speaker(), dialogue.text(), translatedText);
        return rebuilt;
    }

    private static boolean isWynncraftServer(Minecraft minecraft) {
        ServerData server = minecraft == null ? null : minecraft.getCurrentServer();
        String address = server == null ? null : server.ip;
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

    private static ReplacementRange findReplacementRange(Component original, String sourceText) {
        String raw = original.getString();
        StringBuilder literalTreeText = new StringBuilder(raw.length());
        if (!appendLiteralTreeText(original, literalTreeText) || !raw.contentEquals(literalTreeText)) return null;

        int start = raw.indexOf(sourceText);
        if (start < 0 || raw.indexOf(sourceText, start + sourceText.length()) >= 0) return null;
        return new ReplacementRange(raw, start, start + sourceText.length());
    }

    private static boolean appendLiteralTreeText(Component component, StringBuilder output) {
        if (!(component.getContents() instanceof PlainTextContents plainText)) return false;
        output.append(plainText.text());
        for (Component sibling : component.getSiblings()) {
            if (!appendLiteralTreeText(sibling, output)) return false;
        }
        return true;
    }

    private static MutableComponent rebuild(
        Component source,
        Cursor cursor,
        int start,
        int end,
        String translatedText,
        boolean[] inserted
    ) {
        PlainTextContents plainText = (PlainTextContents) source.getContents();
        String text = plainText.text();
        int nodeStart = cursor.offset;
        int nodeEnd = nodeStart + text.length();
        cursor.offset = nodeEnd;

        MutableComponent rebuilt = Component.empty().setStyle(source.getStyle());
        int overlapStart = Math.max(start, nodeStart);
        int overlapEnd = Math.min(end, nodeEnd);
        if (overlapStart >= overlapEnd) {
            if (!text.isEmpty()) rebuilt.append(Component.literal(text).setStyle(source.getStyle()));
        } else {
            int localStart = overlapStart - nodeStart;
            int localEnd = overlapEnd - nodeStart;
            if (localStart > 0) {
                rebuilt.append(Component.literal(text.substring(0, localStart)).setStyle(source.getStyle()));
            }
            if (!inserted[0]) {
                rebuilt.append(Component.literal(translatedText)
                    .setStyle(source.getStyle().withFont(FontDescription.DEFAULT)));
                inserted[0] = true;
            }
            if (localEnd < text.length()) {
                rebuilt.append(Component.literal(text.substring(localEnd)).setStyle(source.getStyle()));
            }
        }

        for (Component sibling : source.getSiblings()) {
            rebuilt.append(rebuild(sibling, cursor, start, end, translatedText, inserted));
        }
        return rebuilt;
    }

    private static synchronized void logDisplayedOnce(String speaker, String source, String target) {
        String signature = speaker + '\u001f' + source + '\u001f' + target;
        if (!LOGGED_DIALOGUES.add(signature)) return;
        while (LOGGED_DIALOGUES.size() > MAX_LOGGED_DIALOGUES) {
            Iterator<String> iterator = LOGGED_DIALOGUES.iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        LOGGER.info(
            "[WAB Dialogue] displayed speaker={} original={} translated={} bodyFont=minecraft:default",
            speaker,
            source,
            target
        );
    }

    private record ReplacementRange(String rawText, int start, int end) {}

    private static final class Cursor {
        private int offset;
    }
}
