package dev.tanaka.wynnaibridge.mixin;

import dev.tanaka.wynnaibridge.capture.FormattedTextUtil;
import dev.tanaka.wynnaibridge.capture.TextCaptureStore;
import dev.tanaka.wynnaibridge.translation.WynncraftTooltipPreview;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.GuiGraphicsExtractor$RenderingTextCollector")
public abstract class RenderingTextCollectorMixin {
    @Inject(method = "accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/client/gui/ActiveTextCollector$Parameters;Lnet/minecraft/util/FormattedCharSequence;)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureActiveText(TextAlignment alignment, int anchorX, int y, ActiveTextCollector.Parameters parameters, FormattedCharSequence text, CallbackInfo ci) {
        TextCaptureStore.INSTANCE.record(
            "activeText." + alignment.name().toLowerCase(),
            WynncraftTooltipPreview.restoreForMcpCapture(FormattedTextUtil.toPlain(text)),
            anchorX,
            y,
            FormattedTextUtil.firstColorHex(text)
        );
    }

    @Inject(method = "acceptScrolling(Lnet/minecraft/network/chat/Component;IIIIILnet/minecraft/client/gui/ActiveTextCollector$Parameters;)V", at = @At("HEAD"), require = 0)
    private void wynnAiBridge$captureScrolling(Component message, int centerX, int left, int right, int top, int bottom, ActiveTextCollector.Parameters parameters, CallbackInfo ci) {
        TextCaptureStore.INSTANCE.record(
            "activeText.scrolling",
            message == null ? "" : message.getString(),
            centerX,
            top,
            FormattedTextUtil.firstColorHex(message)
        );
    }
}
