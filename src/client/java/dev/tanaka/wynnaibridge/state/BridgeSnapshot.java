package dev.tanaka.wynnaibridge.state;

import java.util.List;

public record BridgeSnapshot(
    long capturedAt,
    boolean inWorld,
    PlayerState player,
    ScreenState screen,
    CrosshairState crosshair,
    List<SlotState> slots,
    List<NearbyTextEntityState> nearbyTextEntities
) {
    public record PlayerState(
        String name,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float health,
        float maxHealth,
        int foodLevel
    ) {}

    public record ScreenState(String className, String title, Integer hoveredMenuSlot) {}

    public record CrosshairState(
        String type,
        String name,
        Double distance,
        Integer blockX,
        Integer blockY,
        Integer blockZ
    ) {}

    public record SlotState(
        int menuSlot,
        int containerSlot,
        String itemId,
        String name,
        String nameColor,
        int count
    ) {}

    public record NearbyTextEntityState(
        int entityId,
        String entityType,
        String name,
        String customName,
        String displayText,
        double distance,
        double x,
        double y,
        double z
    ) {}
}
