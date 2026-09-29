package dev.tanaka.wynnaibridge.mixin;

import dev.tanaka.wynnaibridge.capture.FormattedTextUtil;
import dev.tanaka.wynnaibridge.capture.TextCaptureStore;
import dev.tanaka.wynnaibridge.capture.RenderedItemCaptureStore;
import dev.tanaka.wynnaibridge.translation.WynncraftTooltipPreview;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsExtractorMixin {
    @Inject(method = "item(Lnet/minecraft/world/item/ItemStack;II)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureRenderedItem(ItemStack stack, int x, int y, CallbackInfo ci) {
        RenderedItemCaptureStore.INSTANCE.record("gui.item", stack, x, y);
    }

    @Inject(method = "item(Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureRenderedItemSeeded(ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
        RenderedItemCaptureStore.INSTANCE.record("gui.item", stack, x, y);
    }

    @Inject(method = "fakeItem(Lnet/minecraft/world/item/ItemStack;II)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureFakeItem(ItemStack stack, int x, int y, CallbackInfo ci) {
        RenderedItemCaptureStore.INSTANCE.record("gui.fakeItem", stack, x, y);
    }

    @Inject(method = "fakeItem(Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureFakeItemSeeded(ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
        RenderedItemCaptureStore.INSTANCE.record("gui.fakeItem", stack, x, y);
    }
    @Inject(method = "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureString(Font font, String text, int x, int y, int color, boolean dropShadow, CallbackInfo ci) {
        TextCaptureStore.INSTANCE.record("gui.text", WynncraftTooltipPreview.restoreForMcpCapture(text), x, y, FormattedTextUtil.argbHex(color));
    }

    @Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureComponent(Font font, Component text, int x, int y, int color, boolean dropShadow, CallbackInfo ci) {
        TextCaptureStore.INSTANCE.record(
            "gui.component",
            WynncraftTooltipPreview.restoreForMcpCapture(text == null ? "" : text.getString()),
            x,
            y,
            firstNonNull(FormattedTextUtil.firstColorHex(text), FormattedTextUtil.argbHex(color))
        );
    }

    @Inject(method = "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureFormatted(Font font, FormattedCharSequence text, int x, int y, int color, boolean dropShadow, CallbackInfo ci) {
        TextCaptureStore.INSTANCE.record(
            "gui.formatted",
            WynncraftTooltipPreview.restoreForMcpCapture(FormattedTextUtil.toPlain(text)),
            x,
            y,
            firstNonNull(FormattedTextUtil.firstColorHex(text), FormattedTextUtil.argbHex(color))
        );
    }

    @Inject(method = "centeredText(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureCenteredString(Font font, String text, int x, int y, int color, CallbackInfo ci) {
        TextCaptureStore.INSTANCE.record("gui.centered", WynncraftTooltipPreview.restoreForMcpCapture(text), x, y, FormattedTextUtil.argbHex(color));
    }

    @Inject(method = "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureCenteredComponent(Font font, Component text, int x, int y, int color, CallbackInfo ci) {
        TextCaptureStore.INSTANCE.record(
            "gui.centeredComponent",
            WynncraftTooltipPreview.restoreForMcpCapture(text == null ? "" : text.getString()),
            x,
            y,
            firstNonNull(FormattedTextUtil.firstColorHex(text), FormattedTextUtil.argbHex(color))
        );
    }

    @Inject(method = "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureCenteredFormatted(Font font, FormattedCharSequence text, int x, int y, int color, CallbackInfo ci) {
        TextCaptureStore.INSTANCE.record(
            "gui.centeredFormatted",
            WynncraftTooltipPreview.restoreForMcpCapture(FormattedTextUtil.toPlain(text)),
            x,
            y,
            firstNonNull(FormattedTextUtil.firstColorHex(text), FormattedTextUtil.argbHex(color))
        );
    }

    @Inject(method = "setTooltipForNextFrame(Lnet/minecraft/network/chat/Component;II)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureSimpleTooltip(Component component, int x, int y, CallbackInfo ci) {
        TextCaptureStore.INSTANCE.record(
            "tooltip",
            WynncraftTooltipPreview.restoreForMcpCapture(component == null ? "" : component.getString()),
            x,
            y,
            FormattedTextUtil.firstColorHex(component)
        );
    }

    @Inject(method = "setTooltipForNextFrame(Ljava/util/List;II)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureFormattedTooltip(List<FormattedCharSequence> lines, int x, int y, CallbackInfo ci) {
        if (lines == null) return;
        int row = 0;
        for (FormattedCharSequence line : lines) {
            TextCaptureStore.INSTANCE.record(
                "tooltip.line",
                WynncraftTooltipPreview.restoreForMcpCapture(FormattedTextUtil.toPlain(line)),
                x,
                y + row++ * 10,
                FormattedTextUtil.firstColorHex(line)
            );
        }
    }

    @Inject(method = "setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureComponentLines(Font font, List<Component> lines, int x, int y, CallbackInfo ci) {
        captureComponents(lines, x, y);
    }

    private static void captureComponents(List<Component> lines, int x, int y) {
        if (lines == null) return;
        int row = 0;
        for (Component line : lines) {
            TextCaptureStore.INSTANCE.record(
                "tooltip.component",
                WynncraftTooltipPreview.restoreForMcpCapture(line == null ? "" : line.getString()),
                x,
                y + row++ * 10,
                FormattedTextUtil.firstColorHex(line)
            );
        }
    }

    private static String firstNonNull(String first, String second) {
        return first != null ? first : second;
    }
}
