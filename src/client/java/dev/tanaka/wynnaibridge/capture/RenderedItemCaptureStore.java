package dev.tanaka.wynnaibridge.capture;

import dev.tanaka.wynnaibridge.state.ItemInspector;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps a very short-lived cache of ItemStacks that were actually submitted to
 * the GUI renderer. This catches item icons outside ordinary container slots
 * too (merchant lists, custom screens, recipe-style views, etc.).
 */
public final class RenderedItemCaptureStore {
    public static final RenderedItemCaptureStore INSTANCE = new RenderedItemCaptureStore();

    private final Map<String, MutableRenderedItem> entries = new ConcurrentHashMap<>();
    private final AtomicLong records = new AtomicLong();
    private final AtomicLong lastSeen = new AtomicLong();

    private RenderedItemCaptureStore() {}

    public void record(String source, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) return;
        long now = System.currentTimeMillis();
        records.incrementAndGet();
        lastSeen.set(now);

        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String key = source + '\u001f' + x + '\u001f' + y + '\u001f' + itemId + '\u001f' + stack.getHoverName().getString();
        entries.compute(key, (ignored, old) -> {
            if (old == null) {
                return new MutableRenderedItem(source, x, y, stack.copy(), now, now, now, 1L);
            }
            // Item rendering happens every frame. Avoid copying ItemStack/NBT thousands of
            // times per second; refresh the retained snapshot at most four times/second.
            if (now - old.lastSnapshotAt >= 250L || old.stack.getCount() != stack.getCount()) {
                old.stack = stack.copy();
                old.lastSnapshotAt = now;
            }
            old.lastSeen = now;
            old.count++;
            return old;
        });
        if (entries.size() > 2048) prune(now - 5_000L);
    }

    public List<RenderedItemView> snapshot(Minecraft minecraft, long maxAgeMs, int limit, boolean includeTooltips, boolean advanced) {
        long now = System.currentTimeMillis();
        long cutoff = now - Math.max(1L, maxAgeMs);
        prune(now - 10_000L);
        int safeLimit = Math.max(1, Math.min(limit, 1000));

        List<RenderedItemView> out = new ArrayList<>();
        for (MutableRenderedItem value : entries.values()) {
            if (value.lastSeen < cutoff) continue;
            ItemInspector.ItemView item = ItemInspector.inspect(minecraft, value.stack, includeTooltips, advanced);
            if (item == null) continue;
            out.add(new RenderedItemView(
                value.source, value.x, value.y, value.firstSeen, value.lastSeen, value.count, item
            ));
        }
        out.sort(Comparator
            .comparingLong(RenderedItemView::lastSeen)
            .thenComparingInt(RenderedItemView::y)
            .thenComparingInt(RenderedItemView::x));
        if (out.size() > safeLimit) {
            return List.copyOf(out.subList(out.size() - safeLimit, out.size()));
        }
        return List.copyOf(out);
    }

    public Stats stats() {
        return new Stats(records.get(), lastSeen.get(), entries.size());
    }

    private void prune(long cutoff) {
        entries.entrySet().removeIf(entry -> entry.getValue().lastSeen < cutoff);
    }

    public record RenderedItemView(
        String source,
        int x,
        int y,
        long firstSeen,
        long lastSeen,
        long count,
        ItemInspector.ItemView item
    ) {}

    public record Stats(long records, long lastSeen, int cachedEntries) {}

    private static final class MutableRenderedItem {
        private final String source;
        private final int x;
        private final int y;
        private volatile ItemStack stack;
        private final long firstSeen;
        private volatile long lastSeen;
        private volatile long lastSnapshotAt;
        private volatile long count;

        private MutableRenderedItem(String source, int x, int y, ItemStack stack,
                                    long firstSeen, long lastSeen, long lastSnapshotAt, long count) {
            this.source = source;
            this.x = x;
            this.y = y;
            this.stack = stack;
            this.firstSeen = firstSeen;
            this.lastSeen = lastSeen;
            this.lastSnapshotAt = lastSnapshotAt;
            this.count = count;
        }
    }
}
