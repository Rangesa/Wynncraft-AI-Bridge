package dev.tanaka.wynnaibridge.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import dev.tanaka.wynnaibridge.state.UiStateRevisionTracker;

import java.util.ArrayList;
import java.util.List;

/** Only exposes single-slot pickup operations over the local player's vanilla inventory. */
public final class UiActionService {
    private UiActionService() {}

    public static GuiSnapshot capture(Minecraft minecraft, String expectedScreen) {
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            throw new UiActionException("NOT_CONNECTED", "Not connected to a Minecraft world");
        }
        var activeScreen = minecraft.gui.screen();
        if (activeScreen == null) {
            throw new UiActionException("NO_GUI", "Open the player inventory screen first");
        }
        String screenClass = activeScreen.getClass().getName();
        if (expectedScreen != null && !expectedScreen.equals(screenClass)) {
            throw new UiActionException("STATE_CHANGED", "STATE_CHANGED: the current screen no longer matches expectedScreen");
        }
        if (!(activeScreen instanceof InventoryScreen inventoryScreen)
            || !(inventoryScreen.getMenu() instanceof InventoryMenu menu)) {
            throw new UiActionException("UNSAFE_SCREEN", "UI actions currently allow only the vanilla player inventory screen");
        }

        long revision = UiStateRevisionTracker.INSTANCE.refresh(minecraft);
        List<SlotSnapshot> slots = new ArrayList<>(menu.slots.size());
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            int inventorySlot = slot.container == minecraft.player.getInventory() ? slot.getContainerSlot() : -1;
            slots.add(new SlotSnapshot(
                index,
                inventorySlot,
                slot.isActive(),
                slot.mayPickup(minecraft.player),
                item(slot.getItem())
            ));
        }

        return new GuiSnapshot(
            System.currentTimeMillis(),
            screenClass,
            menu.getClass().getName(),
            inventoryScreen.getTitle().getString(),
            System.identityHashCode(inventoryScreen),
            menu.containerId,
            menu.getStateId(),
            revision,
            List.copyOf(slots),
            item(menu.getCarried())
        );
    }

    public static void validateExpectedContext(
        GuiSnapshot actual,
        String expectedScreen,
        int expectedSyncId,
        long expectedRevision
    ) {
        if (!actual.screenClass().equals(expectedScreen)
            || actual.syncId() != expectedSyncId
            || actual.stateRevision() != expectedRevision) {
            throw new UiActionException("STATE_CHANGED", "STATE_CHANGED: screen, syncId, or stateRevision no longer matches");
        }
    }

    public static void validateExpectedItemName(ItemSnapshot actual, String expectedItemName) {
        if (actual == null || actual.empty() || expectedItemName == null || !actual.name().equals(expectedItemName)) {
            throw new UiActionException("EXPECTED_ITEM_MISMATCH", "expectedItemName does not match the selected inventory slot");
        }
    }

    public static void validateMoveDestination(ItemSnapshot source, ItemSnapshot destination) {
        if (source == null || source.empty()) {
            throw new UiActionException("EMPTY_SOURCE", "fromSlot is empty");
        }
        if (destination == null || destination.empty()) return;
        if (!sameItemAndComponents(source, destination)) {
            throw new UiActionException("UNSAFE_DESTINATION", "Destination must be empty or contain the exact same item and components");
        }
        int free = destination.maxStackSize() - destination.count();
        if (free < source.count()) {
            throw new UiActionException("STACK_WOULD_SPLIT", "Destination does not have room for the entire source stack");
        }
    }

    public static ClickPair click(
        Minecraft minecraft,
        int menuSlot,
        int button,
        String expectedItemName,
        String expectedScreen,
        int expectedSyncId,
        long expectedRevision
    ) {
        GuiSnapshot before = capture(minecraft, expectedScreen);
        validateExpectedContext(before, expectedScreen, expectedSyncId, expectedRevision);
        if (button != 0 && button != 1) {
            throw new UiActionException("INVALID_BUTTON", "button must be left or right");
        }
        if (!before.carriedItem().empty()) {
            throw new UiActionException("CURSOR_NOT_EMPTY", "The cursor is carrying an item; use minecraft_move_inventory_item or clear it locally first");
        }
        SlotSnapshot slot = requirePlayerInventorySlot(before, menuSlot);
        if (slot.item().empty()) {
            throw new UiActionException("EMPTY_SLOT", "The selected player inventory slot is empty");
        }
        validateExpectedItemName(slot.item(), expectedItemName);

        performPickup(minecraft, before, menuSlot, button);
        GuiSnapshot after = capture(minecraft, expectedScreen);
        return new ClickPair(before, after, menuSlot, button, slot.item());
    }

    public static MovePlan planMove(
        Minecraft minecraft,
        int fromInventorySlot,
        int toInventorySlot,
        String expectedItemName,
        String expectedScreen,
        int expectedSyncId,
        long expectedRevision
    ) {
        GuiSnapshot before = capture(minecraft, expectedScreen);
        validateExpectedContext(before, expectedScreen, expectedSyncId, expectedRevision);
        if (fromInventorySlot < 0 || fromInventorySlot >= 36 || toInventorySlot < 0 || toInventorySlot >= 36) {
            throw new UiActionException("UNSAFE_SLOT", "Inventory slots must be between 0 and 35");
        }
        if (fromInventorySlot == toInventorySlot) {
            throw new UiActionException("INVALID_MOVE", "fromSlot and toSlot must be different");
        }
        if (!before.carriedItem().empty()) {
            throw new UiActionException("CURSOR_NOT_EMPTY", "The cursor must be empty before moving an inventory item");
        }

        SlotSnapshot source = requirePlayerInventorySlotByInventoryIndex(before, fromInventorySlot);
        SlotSnapshot destination = requirePlayerInventorySlotByInventoryIndex(before, toInventorySlot);
        if (source.item().empty()) {
            throw new UiActionException("EMPTY_SOURCE", "fromSlot is empty");
        }
        validateExpectedItemName(source.item(), expectedItemName);
        validateMoveDestination(source.item(), destination.item());
        return new MovePlan(before, source, destination, source.menuSlot(), destination.menuSlot());
    }

    public static MoveStart startMove(
        Minecraft minecraft,
        int fromInventorySlot,
        int toInventorySlot,
        String expectedItemName,
        String expectedScreen,
        int expectedSyncId,
        long expectedRevision
    ) {
        MovePlan plan = planMove(
            minecraft, fromInventorySlot, toInventorySlot, expectedItemName,
            expectedScreen, expectedSyncId, expectedRevision
        );
        performPickup(minecraft, plan.before(), plan.sourceMenuSlot(), 0);
        return new MoveStart(plan, capture(minecraft, expectedScreen));
    }

    public static EquipPlan planEquip(
        Minecraft minecraft,
        int inventorySlot,
        String expectedItemName,
        String target,
        String expectedScreen,
        int expectedSyncId,
        long expectedRevision
    ) {
        GuiSnapshot before = capture(minecraft, expectedScreen);
        validateExpectedContext(before, expectedScreen, expectedSyncId, expectedRevision);
        if (inventorySlot < 0 || inventorySlot >= 36) {
            throw new UiActionException("UNSAFE_SLOT", "inventorySlot must be between 0 and 35");
        }
        if (!before.carriedItem().empty()) {
            throw new UiActionException("CURSOR_NOT_EMPTY", "The cursor must be empty before equipping an item");
        }

        SlotSnapshot source = requirePlayerInventorySlotByInventoryIndex(before, inventorySlot);
        validateExpectedItemName(source.item(), expectedItemName);
        EquipmentSlot equipmentSlot = armorTarget(target);
        var screen = (InventoryScreen) minecraft.gui.screen();
        ItemStack stack = screen.getMenu().getSlot(source.menuSlot()).getItem();
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        if (equippable == null || equippable.slot() != equipmentSlot) {
            throw new UiActionException("ITEM_NOT_EQUIPPABLE", "The selected item is not equippable in the requested armor slot");
        }

        int targetMenuSlot = -1;
        for (int menuSlot = InventoryMenu.ARMOR_SLOT_START; menuSlot < InventoryMenu.ARMOR_SLOT_END; menuSlot++) {
            Slot armorSlot = screen.getMenu().getSlot(menuSlot);
            if (armorSlot.mayPlace(stack)) {
                if (targetMenuSlot >= 0) {
                    throw new UiActionException("AMBIGUOUS_EQUIPMENT_SLOT", "The vanilla inventory exposed more than one matching armor slot");
                }
                targetMenuSlot = menuSlot;
            }
        }
        if (targetMenuSlot < 0) {
            throw new UiActionException("EQUIPMENT_SLOT_NOT_FOUND", "The requested vanilla armor slot is not available in this inventory screen");
        }
        SlotSnapshot targetSnapshot = before.slots().get(targetMenuSlot);
        if (!targetSnapshot.active() || !targetSnapshot.item().empty()) {
            throw new UiActionException("EQUIPMENT_SLOT_OCCUPIED", "The requested armor slot must be empty; replacing equipped items is not supported");
        }
        return new EquipPlan(before, source, targetSnapshot, source.menuSlot(), targetMenuSlot, equipmentSlot);
    }

    public static EquipStart startEquip(
        Minecraft minecraft,
        int inventorySlot,
        String expectedItemName,
        String target,
        String expectedScreen,
        int expectedSyncId,
        long expectedRevision
    ) {
        EquipPlan plan = planEquip(
            minecraft, inventorySlot, expectedItemName, target, expectedScreen, expectedSyncId, expectedRevision
        );
        performPickup(minecraft, plan.before(), plan.sourceMenuSlot(), 0);
        return new EquipStart(plan, capture(minecraft, expectedScreen));
    }

    public static GuiSnapshot placeEquipTarget(Minecraft minecraft, EquipPlan plan) {
        GuiSnapshot current = capture(minecraft, plan.before().screenClass());
        if (!equipSourcePickupMatches(current, plan)) {
            throw new UiActionException("STATE_CHANGED", "STATE_CHANGED: the expected armor item is no longer carried from its original inventory slot");
        }
        var screen = (InventoryScreen) minecraft.gui.screen();
        Slot target = screen.getMenu().getSlot(plan.targetMenuSlot());
        if (!target.isActive() || !target.getItem().isEmpty() || !target.mayPlace(screen.getMenu().getCarried())) {
            throw new UiActionException("STATE_CHANGED", "STATE_CHANGED: the requested armor slot is no longer empty or compatible");
        }
        performPickup(minecraft, current, plan.targetMenuSlot(), 0);
        return capture(minecraft, plan.before().screenClass());
    }

    public static GuiSnapshot placeMoveDestination(Minecraft minecraft, MovePlan plan) {
        GuiSnapshot current = capture(minecraft, plan.before().screenClass());
        if (!moveSourcePickupMatches(current, plan)) {
            throw new UiActionException("STATE_CHANGED", "STATE_CHANGED: the source pickup is no longer in the expected state");
        }
        performPickup(minecraft, current, plan.destinationMenuSlot(), 0);
        return capture(minecraft, plan.before().screenClass());
    }

    public static GuiSnapshot pickupPlannedSlot(Minecraft minecraft, GuiSnapshot original, int menuSlot, int button) {
        GuiSnapshot current = capture(minecraft, original.screenClass());
        if (current.screenIdentity() != original.screenIdentity() || current.syncId() != original.syncId()) {
            throw new UiActionException("STATE_CHANGED", "STATE_CHANGED: the inventory screen instance or syncId changed");
        }
        SlotSnapshot slot = requirePlayerInventorySlot(current, menuSlot);
        if (!slot.mayPickup() || !slot.active()) {
            throw new UiActionException("SLOT_NOT_INTERACTABLE", "The selected inventory slot is not active or cannot be picked up");
        }
        performPickup(minecraft, current, menuSlot, button);
        return capture(minecraft, original.screenClass());
    }

    public static boolean clickResultMatches(GuiSnapshot current, ClickPair click) {
        if (current.syncId() != click.before().syncId()
            || current.screenIdentity() != click.before().screenIdentity()) return false;
        SlotSnapshot slot = current.slots().get(click.menuSlot());
        int expectedCarriedCount = click.button() == 0
            ? click.clickedItem().count()
            : (click.clickedItem().count() + 1) / 2;
        int expectedSlotCount = click.clickedItem().count() - expectedCarriedCount;
        return sameItemAndComponents(click.clickedItem(), current.carriedItem())
            && current.carriedItem().count() == expectedCarriedCount
            && (expectedSlotCount == 0
                ? slot.item().empty()
                : sameItemAndComponents(click.clickedItem(), slot.item()) && slot.item().count() == expectedSlotCount);
    }

    public static boolean moveSourcePickupMatches(GuiSnapshot current, MovePlan plan) {
        if (!sameScreen(current, plan.before())) return false;
        SlotSnapshot source = current.slots().get(plan.sourceMenuSlot());
        SlotSnapshot destination = current.slots().get(plan.destinationMenuSlot());
        return source.item().empty()
            && sameItemAndComponents(plan.source().item(), current.carriedItem())
            && current.carriedItem().count() == plan.source().item().count()
            && sameStack(destination.item(), plan.destination().item());
    }

    public static boolean moveResultMatches(GuiSnapshot current, MovePlan plan) {
        if (!sameScreen(current, plan.before())) return false;
        SlotSnapshot source = current.slots().get(plan.sourceMenuSlot());
        SlotSnapshot destination = current.slots().get(plan.destinationMenuSlot());
        int expectedDestinationCount = plan.destination().item().count() + plan.source().item().count();
        return source.item().empty()
            && current.carriedItem().empty()
            && sameItemAndComponents(plan.source().item(), destination.item())
            && destination.item().count() == expectedDestinationCount;
    }

    public static boolean equipSourcePickupMatches(GuiSnapshot current, EquipPlan plan) {
        if (!sameScreen(current, plan.before())) return false;
        SlotSnapshot source = current.slots().get(plan.sourceMenuSlot());
        SlotSnapshot target = current.slots().get(plan.targetMenuSlot());
        return source.item().empty()
            && sameItemAndComponents(plan.source().item(), current.carriedItem())
            && current.carriedItem().count() == plan.source().item().count()
            && target.item().empty();
    }

    public static boolean equipResultMatches(GuiSnapshot current, EquipPlan plan) {
        if (!sameScreen(current, plan.before())) return false;
        SlotSnapshot source = current.slots().get(plan.sourceMenuSlot());
        SlotSnapshot target = current.slots().get(plan.targetMenuSlot());
        return source.item().empty()
            && current.carriedItem().empty()
            && sameItemAndComponents(plan.source().item(), target.item())
            && target.item().count() == plan.source().item().count();
    }

    private static boolean sameScreen(GuiSnapshot left, GuiSnapshot right) {
        return left.screenIdentity() == right.screenIdentity() && left.syncId() == right.syncId();
    }

    private static boolean sameStack(ItemSnapshot left, ItemSnapshot right) {
        return left.empty() == right.empty()
            && (left.empty() || (sameItemAndComponents(left, right) && left.count() == right.count()));
    }

    private static boolean sameItemAndComponents(ItemSnapshot left, ItemSnapshot right) {
        return !left.empty() && !right.empty()
            && left.itemId().equals(right.itemId())
            && left.components().equals(right.components());
    }

    private static SlotSnapshot requirePlayerInventorySlotByInventoryIndex(GuiSnapshot snapshot, int inventoryIndex) {
        for (SlotSnapshot slot : snapshot.slots()) {
            if (slot.inventorySlot() == inventoryIndex) {
                if (!slot.active() || !slot.mayPickup()) {
                    throw new UiActionException("SLOT_NOT_INTERACTABLE", "The selected inventory slot is not active or cannot be picked up");
                }
                return slot;
            }
        }
        throw new UiActionException("UNSAFE_SLOT", "The requested player inventory slot is not present in the open inventory screen");
    }

    private static EquipmentSlot armorTarget(String target) {
        if (target == null) throw new UiActionException("INVALID_TARGET", "target must be helmet, chestplate, leggings, or boots");
        return switch (target) {
            case "helmet" -> EquipmentSlot.HEAD;
            case "chestplate" -> EquipmentSlot.CHEST;
            case "leggings" -> EquipmentSlot.LEGS;
            case "boots" -> EquipmentSlot.FEET;
            default -> throw new UiActionException("INVALID_TARGET", "target must be helmet, chestplate, leggings, or boots");
        };
    }

    private static SlotSnapshot requirePlayerInventorySlot(GuiSnapshot snapshot, int menuSlot) {
        if (menuSlot < 0 || menuSlot >= snapshot.slots().size()) {
            throw new UiActionException("SLOT_OUT_OF_RANGE", "slot is outside the current screen handler");
        }
        SlotSnapshot slot = snapshot.slots().get(menuSlot);
        if (slot.inventorySlot() < 0 || slot.inventorySlot() >= 36) {
            throw new UiActionException("UNSAFE_SLOT", "Only the local player's main inventory and hotbar slots can be changed");
        }
        if (!slot.active() || !slot.mayPickup()) {
            throw new UiActionException("SLOT_NOT_INTERACTABLE", "The selected inventory slot is not active or cannot be picked up");
        }
        return slot;
    }

    private static void performPickup(Minecraft minecraft, GuiSnapshot snapshot, int menuSlot, int button) {
        var player = minecraft.player;
        var screen = minecraft.gui.screen();
        if (!(screen instanceof InventoryScreen inventoryScreen)
            || !(inventoryScreen.getMenu() instanceof InventoryMenu)
            || inventoryScreen.getMenu().containerId != snapshot.syncId()) {
            throw new UiActionException("STATE_CHANGED", "STATE_CHANGED: the current inventory screen changed before click execution");
        }
        minecraft.gameMode.handleContainerInput(
            snapshot.syncId(), menuSlot, button, ContainerInput.PICKUP, player
        );
    }

    private static ItemSnapshot item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemSnapshot.EMPTY;
        return new ItemSnapshot(
            false,
            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
            stack.getHoverName().getString(),
            stack.getCount(),
            stack.getComponents().toString(),
            stack.getMaxStackSize()
        );
    }

    public record ItemSnapshot(
        boolean empty,
        String itemId,
        String name,
        int count,
        String components,
        int maxStackSize
    ) {
        private static final ItemSnapshot EMPTY = new ItemSnapshot(true, "", "", 0, "", 0);
    }

    public record SlotSnapshot(
        int menuSlot,
        int inventorySlot,
        boolean active,
        boolean mayPickup,
        ItemSnapshot item
    ) {}

    public record GuiSnapshot(
        long capturedAt,
        String screenClass,
        String menuClass,
        String title,
        int screenIdentity,
        int syncId,
        int stateId,
        long stateRevision,
        List<SlotSnapshot> slots,
        ItemSnapshot carriedItem
    ) {}

    public record ClickPair(GuiSnapshot before, GuiSnapshot after, int menuSlot, int button, ItemSnapshot clickedItem) {}
    public record MovePlan(GuiSnapshot before, SlotSnapshot source, SlotSnapshot destination,
                           int sourceMenuSlot, int destinationMenuSlot) {}
    public record MoveStart(MovePlan plan, GuiSnapshot afterSourcePickup) {}
    public record EquipPlan(GuiSnapshot before, SlotSnapshot source, SlotSnapshot target,
                            int sourceMenuSlot, int targetMenuSlot, EquipmentSlot equipmentSlot) {}
    public record EquipStart(EquipPlan plan, GuiSnapshot afterSourcePickup) {}

    public static final class UiActionException extends IllegalStateException {
        private final String code;

        public UiActionException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
