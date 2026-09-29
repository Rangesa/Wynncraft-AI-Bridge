package dev.tanaka.wynnaibridge.state;

import dev.tanaka.wynnaibridge.capture.FormattedTextUtil;
import dev.tanaka.wynnaibridge.capture.WynnTextSanitizer;
import dev.tanaka.wynnaibridge.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class StateCollector {
    public static final StateCollector INSTANCE = new StateCollector();

    private static final double NEARBY_TEXT_RADIUS = 32.0;
    private static final int MAX_NEARBY_TEXT_ENTITIES = 128;
    private final AtomicReference<BridgeSnapshot> latest = new AtomicReference<>(empty());

    private StateCollector() {}

    public BridgeSnapshot latest() {
        return latest.get();
    }

    public void capture(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        boolean inWorld = player != null && minecraft.level != null;

        BridgeSnapshot.PlayerState playerState = null;
        if (player != null) {
            playerState = new BridgeSnapshot.PlayerState(
                WynnTextSanitizer.sanitize(player.getName().getString()),
                finite(player.getX()), finite(player.getY()), finite(player.getZ()),
                finite(player.getYRot()), finite(player.getXRot()),
                finite(player.getHealth()), finite(player.getMaxHealth()),
                player.getFoodData().getFoodLevel()
            );
        }

        Screen currentScreen = minecraft.gui.screen();
        List<BridgeSnapshot.SlotState> slots = List.of();
        Integer hoveredMenuSlot = null;
        if (currentScreen instanceof AbstractContainerScreen<?> containerScreen) {
            List<BridgeSnapshot.SlotState> slotList = new ArrayList<>();
            var menu = containerScreen.getMenu();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                ItemStack stack = slot.getItem();
                if (stack.isEmpty()) continue;
                Component hoverName = stack.getHoverName();
                slotList.add(new BridgeSnapshot.SlotState(
                    i,
                    slot.getContainerSlot(),
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                    WynnTextSanitizer.sanitize(hoverName.getString()),
                    FormattedTextUtil.firstColorHex(hoverName),
                    stack.getCount()
                ));
            }
            slots = List.copyOf(slotList);
            Slot hovered = ((AbstractContainerScreenAccessor) containerScreen).wynnAiBridge$getHoveredSlot();
            if (hovered != null) {
                int index = menu.slots.indexOf(hovered);
                if (index >= 0) hoveredMenuSlot = index;
            }
        }

        BridgeSnapshot.ScreenState screenState = currentScreen == null
            ? new BridgeSnapshot.ScreenState("", "", null)
            : new BridgeSnapshot.ScreenState(
                currentScreen.getClass().getName(),
                WynnTextSanitizer.sanitize(currentScreen.getTitle().getString()),
                hoveredMenuSlot
            );

        BridgeSnapshot.CrosshairState crosshairState = crosshair(minecraft, player);
        List<BridgeSnapshot.NearbyTextEntityState> nearbyTextEntities = nearbyTextEntities(minecraft, player);

        latest.set(new BridgeSnapshot(
            System.currentTimeMillis(),
            inWorld,
            playerState,
            screenState,
            crosshairState,
            slots,
            nearbyTextEntities
        ));
    }

    private static List<BridgeSnapshot.NearbyTextEntityState> nearbyTextEntities(Minecraft minecraft, LocalPlayer player) {
        if (minecraft.level == null || player == null) return List.of();
        double maxDistanceSqr = NEARBY_TEXT_RADIUS * NEARBY_TEXT_RADIUS;
        List<BridgeSnapshot.NearbyTextEntityState> out = new ArrayList<>();

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity == player) continue;
            double distanceSqr = player.distanceToSqr(entity);
            if (!Double.isFinite(distanceSqr) || distanceSqr > maxDistanceSqr) continue;

            Component customNameComponent = entity.getCustomName();
            String customName = customNameComponent == null ? "" : WynnTextSanitizer.sanitize(customNameComponent.getString());
            String displayText = "";
            if (entity instanceof Display.TextDisplay textDisplay) {
                Display.TextDisplay.TextRenderState state = textDisplay.textRenderState();
                if (state != null && state.text() != null) {
                    displayText = WynnTextSanitizer.sanitize(state.text().getString());
                }
            }

            if (customName.isBlank() && displayText.isBlank()) continue;

            out.add(new BridgeSnapshot.NearbyTextEntityState(
                entity.getId(),
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                WynnTextSanitizer.sanitize(entity.getName().getString()),
                customName,
                displayText,
                Math.sqrt(distanceSqr),
                finite(entity.getX()), finite(entity.getY()), finite(entity.getZ())
            ));
        }

        out.sort(Comparator.comparingDouble(BridgeSnapshot.NearbyTextEntityState::distance));
        if (out.size() > MAX_NEARBY_TEXT_ENTITIES) {
            return List.copyOf(out.subList(0, MAX_NEARBY_TEXT_ENTITIES));
        }
        return List.copyOf(out);
    }

    private static BridgeSnapshot.CrosshairState crosshair(Minecraft minecraft, LocalPlayer player) {
        HitResult hit = minecraft.hitResult;
        if (hit == null) return new BridgeSnapshot.CrosshairState("MISS", "", null, null, null, null);

        if (hit instanceof EntityHitResult entityHit) {
            double distance = player == null ? 0.0 : player.distanceTo(entityHit.getEntity());
            return new BridgeSnapshot.CrosshairState(
                "ENTITY",
                WynnTextSanitizer.sanitize(entityHit.getEntity().getName().getString()),
                finite(distance),
                null, null, null
            );
        }

        if (hit instanceof BlockHitResult blockHit) {
            var pos = blockHit.getBlockPos();
            String name = minecraft.level == null
                ? ""
                : WynnTextSanitizer.sanitize(minecraft.level.getBlockState(pos).getBlock().getName().getString());
            double distanceSqr = player == null
                ? 0.0
                : player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            return new BridgeSnapshot.CrosshairState(
                "BLOCK",
                name,
                finite(Math.sqrt(distanceSqr)),
                pos.getX(), pos.getY(), pos.getZ()
            );
        }

        return new BridgeSnapshot.CrosshairState(hit.getType().name(), "", null, null, null, null);
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0F;
    }

    private static BridgeSnapshot empty() {
        return new BridgeSnapshot(
            System.currentTimeMillis(), false, null,
            new BridgeSnapshot.ScreenState("", "", null),
            new BridgeSnapshot.CrosshairState("MISS", "", null, null, null, null),
            List.of(), List.of()
        );
    }
}
