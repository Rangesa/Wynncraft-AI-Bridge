package dev.tanaka.wynnaibridge.state;

import dev.tanaka.wynnaibridge.capture.RenderedItemCaptureStore;
import dev.tanaka.wynnaibridge.capture.TextCaptureStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import dev.tanaka.wynnaibridge.state.TooltipCollector;

import java.util.List;

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
            "unclassified",
            "Ability Tree semantics are not inferred until this runtime screen has been inspected."
        );
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
        String abilityTreeClassification,
        String classificationNote
    ) {}
}
