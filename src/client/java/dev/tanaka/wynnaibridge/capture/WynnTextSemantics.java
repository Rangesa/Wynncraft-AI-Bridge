package dev.tanaka.wynnaibridge.capture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small deterministic semantic layer for the most useful Wynncraft text.
 * It deliberately does not pretend to understand every custom HUD format.
 */
public final class WynnTextSemantics {
    private static final Pattern CONTINUE_DIALOGUE = Pattern.compile(
        "(?is).*?\\bto continue\\b\\s*(.+?[.!?…])\\s+([\\p{L}\\p{N}][\\p{L}\\p{N} ._'’&()\\-]{0,63})$"
    );

    private WynnTextSemantics() {}

    public static List<Dialogue> dialogues(
        List<TextCaptureStore.CapturedText> visible,
        List<MessageStore.Message> messages
    ) {
        Map<String, Dialogue> dedup = new LinkedHashMap<>();

        for (TextCaptureStore.CapturedText item : visible) {
            Dialogue parsed = parse(item.text(), item.source(), item.firstSeen(), item.lastSeen());
            merge(dedup, parsed);
        }
        for (MessageStore.Message message : messages) {
            Dialogue parsed = parse(message.text(), "message:" + message.kind(), message.time(), message.time());
            merge(dedup, parsed);
        }

        List<Dialogue> out = new ArrayList<>(dedup.values());
        out.sort(Comparator.comparingLong(Dialogue::lastSeen));
        return List.copyOf(out);
    }

    private static Dialogue parse(String input, String source, long firstSeen, long lastSeen) {
        String text = WynnTextSanitizer.sanitize(input);
        if (text.isBlank()) return null;

        Matcher matcher = CONTINUE_DIALOGUE.matcher(text);
        if (!matcher.matches()) return null;

        String dialogue = matcher.group(1).strip();
        String speaker = matcher.group(2).strip();
        if (dialogue.isBlank() || speaker.isBlank()) return null;
        if (speaker.length() > 64 || dialogue.length() > 1200) return null;

        return new Dialogue(source, speaker, dialogue, true, firstSeen, lastSeen);
    }

    /** Parses one rendered Component using the same canonical dialogue rules used by the Bridge API. */
    public static Dialogue parseDialogue(String input, String source) {
        long now = System.currentTimeMillis();
        return parse(input, source == null ? "rendered" : source, now, now);
    }

    private static void merge(Map<String, Dialogue> dedup, Dialogue value) {
        if (value == null) return;
        String key = WynnTextSanitizer.comparisonKey(value.speaker()) + '\u001f'
            + WynnTextSanitizer.comparisonKey(value.text());
        Dialogue old = dedup.get(key);
        if (old == null) {
            dedup.put(key, value);
            return;
        }
        dedup.put(key, new Dialogue(
            old.source(),
            old.speaker(),
            old.text(),
            old.canContinue() || value.canContinue(),
            Math.min(old.firstSeen(), value.firstSeen()),
            Math.max(old.lastSeen(), value.lastSeen())
        ));
    }

    public record Dialogue(
        String source,
        String speaker,
        String text,
        boolean canContinue,
        long firstSeen,
        long lastSeen
    ) {}
}
