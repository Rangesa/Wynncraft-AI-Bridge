package dev.tanaka.wynnaibridge.capture;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MessageStore {
    public static final MessageStore INSTANCE = new MessageStore(500);

    private final int capacity;
    private final ArrayDeque<Message> messages = new ArrayDeque<>();
    private final Map<String, Long> kindCounts = new LinkedHashMap<>();

    private MessageStore(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void add(String kind, String text) {
        String cleaned = WynnTextSanitizer.sanitize(text);
        if (cleaned.isBlank()) return;
        messages.addLast(new Message(System.currentTimeMillis(), kind, cleaned));
        kindCounts.merge(kind, 1L, Long::sum);
        while (messages.size() > capacity) messages.removeFirst();
    }

    public synchronized List<Message> snapshot(int limit) {
        return snapshotSince(0L, null, limit);
    }

    public synchronized List<Message> snapshotSince(long sinceExclusive, String kindFilter, int limit) {
        int wanted = Math.max(1, Math.min(limit, capacity));
        List<Message> out = new ArrayList<>();
        for (Message message : messages) {
            if (message.time() <= sinceExclusive) continue;
            if (kindFilter != null && !kindFilter.isBlank() && !message.kind().equals(kindFilter)) continue;
            out.add(message);
        }
        int from = Math.max(0, out.size() - wanted);
        return List.copyOf(out.subList(from, out.size()));
    }

    /**
     * Returns true when a rendered line appears to be an echo of a player chat
     * message already observed through Fabric's dedicated CHAT event. This closes
     * the privacy hole where includeChat=false hid MessageStore chat entries but
     * the same line re-entered through GUI text capture.
     */
    public synchronized boolean isChatEcho(String visibleText) {
        String candidate = WynnTextSanitizer.comparisonKey(visibleText);
        if (candidate.isBlank()) return false;

        for (Message message : messages) {
            if (!"chat".equals(message.kind())) continue;
            String chat = WynnTextSanitizer.comparisonKey(message.text());
            if (chat.isBlank()) continue;
            if (candidate.equals(chat) || candidate.endsWith(chat) || chat.endsWith(candidate)) return true;
        }
        return false;
    }

    public synchronized Map<String, Object> stats() {
        return Map.of(
            "stored", messages.size(),
            "capacity", capacity,
            "countsByKind", Map.copyOf(kindCounts)
        );
    }

    public record Message(long time, String kind, String text) {}
}
