package dev.tanaka.wynnaibridge.mixin;

import dev.tanaka.wynnaibridge.translation.WynncraftDialoguePreview;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Translates only the completed, semantically parsed HUD dialogue Component. */
@Mixin(Hud.class)
public abstract class HudDialogueTranslationMixin {
    @ModifyArgs(
        method = "extractOverlayMessage(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"
        ),
        require = 1
    )
    private void wynnAiBridge$translateCompletedDialogue(Args args) {
        Component original = (Component) args.get(1);
        Component translated = WynncraftDialoguePreview.translateCompletedDialogue(original);
        if (translated == original) return;

        Font font = (Font) args.get(0);
        int width = font.width(translated);
        args.set(1, translated);
        args.set(2, -width / 2);
        args.set(4, width);
    }
}
