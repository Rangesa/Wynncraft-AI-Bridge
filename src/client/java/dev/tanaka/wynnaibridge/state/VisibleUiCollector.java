package dev.tanaka.wynnaibridge.state;

import dev.tanaka.wynnaibridge.capture.RenderedItemCaptureStore;
import dev.tanaka.wynnaibridge.capture.TextCaptureStore;
import dev.tanaka.wynnaibridge.capture.WynnTextSemantics;
import net.minecraft.client.Minecraft;

import java.util.List;

/**
 * High-level snapshot of what the local player can currently inspect without
 * image OCR: pre-render text, rendered item icons, open container contents,
 * and optionally the player's own inventory/equipment.
 */
public final class VisibleUiCollector {
    private VisibleUiCollector() {}

    public static Result collect(
        Minecraft minecraft,
        long maxAgeMs,
        int textLimit,
        int itemLimit,
        boolean includeTooltips,
        boolean advanced,
        boolean includeInventory,
        boolean includeChat
    ) {
        long now = System.currentTimeMillis();
        List<TextCaptureStore.CapturedText> text = TextCaptureStore.INSTANCE.snapshotSince(
            0L, maxAgeMs, null, textLimit, includeChat
        );
        List<RenderedItemCaptureStore.RenderedItemView> renderedItems = RenderedItemCaptureStore.INSTANCE.snapshot(
            minecraft, maxAgeMs, itemLimit, includeTooltips, advanced
        );

        OpenContainerCollector.Result container = OpenContainerCollector.collect(
            minecraft, false, includeTooltips, advanced
        );
        if (!container.ok()) container = null;

        PlayerInventoryCollector.Result inventory = includeInventory
            ? PlayerInventoryCollector.collect(minecraft, false, includeTooltips, advanced)
            : null;
        if (inventory != null && !inventory.ok()) inventory = null;

        return new Result(
            now,
            StateCollector.INSTANCE.latest(),
            text,
            WynnTextSemantics.dialogues(text, List.of()),
            renderedItems,
            container,
            inventory,
            includeChat
        );
    }

    public record Result(
        long capturedAt,
        BridgeSnapshot state,
        List<TextCaptureStore.CapturedText> visibleText,
        List<WynnTextSemantics.Dialogue> dialogues,
        List<RenderedItemCaptureStore.RenderedItemView> renderedItems,
        OpenContainerCollector.Result openContainer,
        PlayerInventoryCollector.Result playerInventory,
        boolean chatIncluded
    ) {}
}
