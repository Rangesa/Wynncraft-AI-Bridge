package dev.tanaka.wynnaibridge.state;

import dev.tanaka.wynnaibridge.capture.RenderedItemCaptureStore;
import dev.tanaka.wynnaibridge.capture.TextCaptureStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import dev.tanaka.wynnaibridge.state.TooltipCollector;

import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

/** Read-only runtime evidence collector; it deliberately does not infer ability states or click targets. */
public final class AbilityTreeDebugCollector {
    private AbilityTreeDebugCollector() {}

    public static Result collect(Minecraft minecraft, long maxAgeMs, int itemLimit, int textLimit) {
        var screen = minecraft.gui.screen();
        long revision = UiStateRevisionTracker.INSTANCE.refresh(minecraft);
        String screenClass = screen == null ? null : screen.getClass().getName();
        String screenTitle = screen == null ? null : screen.getTitle().getString();
        OpenContainerCollector.Result container = OpenContainerCollector.collect(minecraft, true, true, false);
        if (!container.ok()) container = null;
        TooltipCollector.Result hoveredItem = TooltipCollector.collect(minecraft, null, false);
        List<RenderedItemCaptureStore.RenderedItemView> renderedItems = RenderedItemCaptureStore.INSTANCE.snapshot(
            minecraft, maxAgeMs, itemLimit, true, false
        );
        List<TextCaptureStore.CapturedText> visibleText = TextCaptureStore.INSTANCE.snapshotSince(
            0L, maxAgeMs, null, textLimit, false
        );
        List<RefundEvidence> refundEvidence = new ArrayList<>();
        for (TextCaptureStore.CapturedText line : visibleText) {
            if (refundRelated(line.text())) refundEvidence.add(new RefundEvidence(line.source(), null, line.text()));
        }
        if (container != null) {
            for (OpenContainerCollector.SlotView slot : container.slots()) {
                if (slot.item() == null) continue;
                for (ItemInspector.TooltipLine line : slot.item().tooltip()) {
                    if (refundRelated(line.text())) refundEvidence.add(new RefundEvidence("container-tooltip", slot.menuSlot(), line.text()));
                }
            }
        }
        for (RenderedItemCaptureStore.RenderedItemView rendered : renderedItems) {
            for (ItemInspector.TooltipLine line : rendered.item().tooltip()) {
                if (refundRelated(line.text())) refundEvidence.add(new RefundEvidence("rendered-tooltip", null, line.text()));
            }
        }
        return new Result(
            System.currentTimeMillis(),
            revision,
            screenClass,
            screenTitle,
            screen instanceof AbstractContainerScreen<?> containerScreen
                ? containerScreen.getMenu().getClass().getName()
                : null,
            container == null ? null : container.containerId(),
            container == null ? null : container.stateId(),
            container,
            hoveredItem,
            renderedItems,
            visibleText,
            List.copyOf(refundEvidence),
            "unclassified",
            "Refund/reset evidence is an exact read-only capture only; visibility does not establish cost or execution semantics."
        );
    }

    private static boolean refundRelated(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return text.contains("refund") || text.contains("reset") || text.contains("ability shard")
            || text.contains("undo") || text.contains("confirm");
    }

    public record Result(
        long capturedAt,
        long stateRevision,
        String screenClass,
        String screenTitle,
        String menuClass,
        Integer syncId,
        Integer menuStateId,
        OpenContainerCollector.Result openContainer,
        TooltipCollector.Result hoveredItem,
        List<RenderedItemCaptureStore.RenderedItemView> renderedItems,
        List<TextCaptureStore.CapturedText> visibleText,
        List<RefundEvidence> refundResetEvidence,
        String abilityTreeClassification,
        String classificationNote
    ) {}

    public record RefundEvidence(String source, Integer menuSlot, String exactText) {}
}
