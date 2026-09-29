package dev.tanaka.wynnaibridge.capture;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class TextCaptureStore {
    public static final TextCaptureStore INSTANCE = new TextCaptureStore();

    private static final int MAX_TEXT_LENGTH = 4096;
    private final Map<String, MutableText> entries = new ConcurrentHashMap<>();
    private final Map<String, MutableSourceStats> sourceStats = new ConcurrentHashMap<>();

    private TextCaptureStore() {}

    public void record(String source, String text, int x, int y) {
        record(source, text, x, y, null);
    }

    public void record(String source, String text, int x, int y, String color) {
        if (text == null) return;
        String raw = text.replace('\u0000', ' ').strip();
        if (raw.isEmpty()) return;
        if (raw.length() > MAX_TEXT_LENGTH) raw = raw.substring(0, MAX_TEXT_LENGTH);

        String cleaned = WynnTextSanitizer.sanitize(raw);
        if (cleaned.isEmpty()) return;
        if (cleaned.length() > MAX_TEXT_LENGTH) cleaned = cleaned.substring(0, MAX_TEXT_LENGTH);

        long now = System.currentTimeMillis();
        MutableSourceStats stats = sourceStats.computeIfAbsent(source, ignored -> new MutableSourceStats());
        stats.records.increment();
        stats.lastSeen.set(now);

        // Intentionally key on sanitized text rather than raw glyph payload. Wynncraft
        // changes rendering-only PUA glyphs frequently even when the visible sentence
        // is identical; treating those as distinct records created noisy snapshots.
        String key = source + '\u001f' + x + '\u001f' + y + '\u001f' + cleaned;
        final String finalText = cleaned;
        final boolean hadRenderingNoise = WynnTextSanitizer.hadRenderingNoise(raw);
        entries.compute(key, (ignored, old) -> {
            if (old == null) return new MutableText(source, finalText, x, y, color, hadRenderingNoise, now, now, 1L);
            old.lastSeen = now;
            old.count++;
            old.hadRenderingNoise |= hadRenderingNoise;
            return old;
        });

        if (entries.size() > 4096) prune(now - 10_000L);
    }

    public List<CapturedText> snapshot(long maxAgeMillis) {
        return snapshotSince(0L, maxAgeMillis, null, 1000, true);
    }

    public List<CapturedText> snapshotSince(long sinceExclusive, long maxAgeMillis, String sourceFilter, int limit) {
        return snapshotSince(sinceExclusive, maxAgeMillis, sourceFilter, limit, true);
    }

    public List<CapturedText> snapshotSince(
        long sinceExclusive,
        long maxAgeMillis,
        String sourceFilter,
        int limit,
        boolean includeChat
    ) {
        long now = System.currentTimeMillis();
        long cutoff = Math.max(sinceExclusive + 1L, now - Math.max(1L, maxAgeMillis));
        prune(now - 30_000L);

        int safeLimit = Math.max(1, Math.min(limit, 2000));
        List<CapturedText> candidates = new ArrayList<>();
        for (MutableText value : entries.values()) {
            if (value.lastSeen < cutoff) continue;
            if (!matchesSource(value.source, sourceFilter)) continue;
            if (!includeChat && MessageStore.INSTANCE.isChatEcho(value.text)) continue;
            candidates.add(new CapturedText(
                value.source, value.text, value.x, value.y, value.color, value.hadRenderingNoise,
                value.firstSeen, value.lastSeen, value.count
            ));
        }

        // Mixin paths such as gui.component/gui.formatted/gui.text may all observe
        // the same draw call. Collapse those path duplicates while retaining
        // positional distinctions that can matter to an AI interpreting a menu.
        Map<String, CapturedText> dedup = new LinkedHashMap<>();
        candidates.sort(Comparator
            .comparingLong(CapturedText::lastSeen)
            .thenComparingInt(CapturedText::y)
            .thenComparingInt(CapturedText::x)
            .thenComparing(CapturedText::text));
        for (CapturedText item : candidates) {
            String key = sourceFamily(item.source()) + '\u001f' + item.x() + '\u001f' + item.y() + '\u001f' + item.text();
            CapturedText old = dedup.get(key);
            if (old == null) {
                dedup.put(key, item);
            } else {
                dedup.put(key, new CapturedText(
                    preferredSource(old.source(), item.source()),
                    old.text(),
                    old.x(),
                    old.y(),
                    old.color() != null ? old.color() : item.color(),
                    old.hadRenderingNoise() || item.hadRenderingNoise(),
                    Math.min(old.firstSeen(), item.firstSeen()),
                    Math.max(old.lastSeen(), item.lastSeen()),
                    old.count() + item.count()
                ));
            }
        }

        List<CapturedText> out = new ArrayList<>(dedup.values());
        out.sort(Comparator
            .comparingLong(CapturedText::lastSeen)
            .thenComparingInt(CapturedText::y)
            .thenComparingInt(CapturedText::x)
            .thenComparing(CapturedText::text));
        if (out.size() > safeLimit) {
            return List.copyOf(out.subList(out.size() - safeLimit, out.size()));
        }
        return List.copyOf(out);
    }

    public Map<String, SourceStats> stats() {
        Map<String, SourceStats> out = new LinkedHashMap<>();
        sourceStats.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> out.put(entry.getKey(), new SourceStats(
                entry.getValue().records.sum(),
                entry.getValue().lastSeen.get()
            )));
        return out;
    }

    private static boolean matchesSource(String source, String filter) {
        if (filter == null || filter.isBlank()) return true;
        if (filter.endsWith("*")) return source.startsWith(filter.substring(0, filter.length() - 1));
        return source.equals(filter);
    }

    private static String sourceFamily(String source) {
        if (source.startsWith("gui.")) return "gui";
        if (source.startsWith("activeText.")) return "activeText";
        return source;
    }

    private static String preferredSource(String a, String b) {
        return sourcePriority(a) <= sourcePriority(b) ? a : b;
    }

    private static int sourcePriority(String source) {
        return switch (source) {
            case "gui.component" -> 0;
            case "gui.formatted" -> 1;
            case "gui.text" -> 2;
            case "gui.centeredComponent" -> 3;
            default -> 10;
        };
    }

    private void prune(long cutoff) {
        entries.entrySet().removeIf(entry -> entry.getValue().lastSeen < cutoff);
    }

    public record CapturedText(
        String source,
        String text,
        int x,
        int y,
        String color,
        boolean hadRenderingNoise,
        long firstSeen,
        long lastSeen,
        long count
    ) {}

    public record SourceStats(long records, long lastSeen) {}

    private static final class MutableText {
        private final String source;
        private final String text;
        private final int x;
        private final int y;
        private final String color;
        private final long firstSeen;
        private volatile boolean hadRenderingNoise;
        private volatile long lastSeen;
        private volatile long count;

        private MutableText(String source, String text, int x, int y, String color,
                            boolean hadRenderingNoise, long firstSeen, long lastSeen, long count) {
            this.source = source;
            this.text = text;
            this.x = x;
            this.y = y;
            this.color = color;
            this.hadRenderingNoise = hadRenderingNoise;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.count = count;
        }
    }

    private static final class MutableSourceStats {
        private final LongAdder records = new LongAdder();
        private final AtomicLong lastSeen = new AtomicLong();
    }
}
