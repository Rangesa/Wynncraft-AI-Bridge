package dev.tanaka.wynnaibridge.mixin;

import dev.tanaka.wynnaibridge.translation.WynncraftTooltipPreview;
import dev.tanaka.wynnaibridge.translation.AbilityTreeTooltipPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.List;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenTooltipMixin {
    @ModifyArgs(
        method = "extractTooltip(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V"
        ),
        require = 1
    )
    private void wynnAiBridge$replaceWynncraftTooltipRows(Args args) {
        Slot slot = ((AbstractContainerScreenAccessor) (Object) this).wynnAiBridge$getHoveredSlot();
        ItemStack stack = slot == null ? null : slot.getItem();

        @SuppressWarnings("unchecked")
        List<Component> originalLines = (List<Component>) args.get(1);
        AbilityTreeTooltipPreview.PreviewResult abilityTree = AbilityTreeTooltipPreview.interceptContainerTooltip(
            Minecraft.getInstance(), stack, originalLines, args.get(3), args.get(4)
        );
        if (abilityTree.handled()) {
            if (abilityTree.modifiedLines() > 0) args.set(1, abilityTree.lines());
            return;
        }

        if (originalLines == null) {
            WynncraftTooltipPreview.interceptContainerTooltip(
                Minecraft.getInstance(), stack, null, args.get(3), args.get(4)
            );
            return;
        }

        WynncraftTooltipPreview.PreviewResult preview = WynncraftTooltipPreview.interceptContainerTooltip(
            Minecraft.getInstance(), stack, originalLines, args.get(3), args.get(4)
        );
        if (preview.modifiedLines() > 0) args.set(1, preview.lines());
    }
}
