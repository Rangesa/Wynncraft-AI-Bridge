package dev.tanaka.wynnaibridge.ui;

import dev.tanaka.wynnaibridge.state.UiStateRevisionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Count-one, standard-pickup Bank deposit to an internally selected empty Bank slot. */
public final class BankDepositService {
    private static final int BANK_CONTENT_SLOT_MIN = 0;
    private static final int BANK_CONTENT_SLOT_MAX = 44;

    private BankDepositService() {}

    public static UiActionService.GuiSnapshot capture(Minecraft minecraft, String expectedScreen) {
        return BankWithdrawService.capture(minecraft, expectedScreen);
    }

    public static DepositPlan plan(
        UiActionService.GuiSnapshot before,
        int bankPage,
        int inventorySlot,
        String expectedItemId,
        String expectedItemName,
        String expectedScreen,
        int expectedSyncId,
        long expectedRevision
    ) {
        UiActionService.validateExpectedContext(before, expectedScreen, expectedSyncId, expectedRevision);
        BankWithdrawService.validateBankPage(before, bankPage);
        if (inventorySlot < 0 || inventorySlot >= 36) {
            throw new UiActionService.UiActionException("UNSAFE_SLOT", "inventorySlot must be between 0 and 35");
        }
        if (!before.carriedItem().empty()) {
            throw new UiActionService.UiActionException("CURSOR_NOT_EMPTY", "The cursor must be empty before depositing a Bank item");
        }
        if (expectedItemId == null || expectedItemId.isBlank()) {
            throw new UiActionService.UiActionException("EXPECTED_ITEM_MISMATCH", "expectedItemId is required");
        }
        UiActionService.SlotSnapshot source = playerInventorySlot(before, inventorySlot);
        UiActionService.validateExpectedItemName(source.item(), expectedItemName);
        if (source.item().empty() || !source.item().itemId().equals(expectedItemId)) {
            throw new UiActionService.UiActionException("EXPECTED_ITEM_MISMATCH", "The inventory item id does not match the expected identity");
        }
        if (source.item().count() != 1) {
            throw new UiActionService.UiActionException("BANK_STACK_NOT_SUPPORTED", "Only one single-count item can be deposited per action");
        }
        UiActionService.SlotSnapshot destination = before.slots().stream()
            .filter(slot -> slot.menuSlot() >= BANK_CONTENT_SLOT_MIN && slot.menuSlot() <= BANK_CONTENT_SLOT_MAX)
            .filter(slot -> slot.inventorySlot() < 0 && slot.active() && slot.item().empty())
            .findFirst()
            .orElseThrow(() -> new UiActionService.UiActionException("NO_EMPTY_BANK_SLOT", "No empty recognized Bank content slot is available"));
        return new DepositPlan(before, source, destination, source.menuSlot(), destination.menuSlot(), bankPage);
    }

    public static DepositStart start(Minecraft minecraft, DepositPlan plan) {
        UiActionService.GuiSnapshot current = capture(minecraft, plan.before().screenClass());
        if (!sameInitialState(current, plan)) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "The Bank page, revision, item, cursor, or destination changed before deposit");
        }
        ContainerScreen screen = (ContainerScreen) minecraft.gui.screen();
        Slot source = screen.getMenu().getSlot(plan.inventoryMenuSlot());
        Slot destination = screen.getMenu().getSlot(plan.bankMenuSlot());
        ItemStack stack = source.getItem();
        if (!destination.mayPlace(stack)) {
            throw new UiActionService.UiActionException("DESTINATION_NOT_ALLOWED", "The selected Bank slot cannot accept this item");
        }
        performPickup(minecraft, current, plan.inventoryMenuSlot());
        return new DepositStart(plan, capture(minecraft, plan.before().screenClass()));
    }

    public static boolean sourcePickupMatches(UiActionService.GuiSnapshot current, DepositPlan plan) {
        if (!sameScreen(current, plan.before()) || !BankWithdrawService.recognizedBankPage(current, plan.bankPage())) return false;
        return current.slots().get(plan.inventoryMenuSlot()).item().empty()
            && sameStack(plan.source().item(), current.carriedItem())
            && current.slots().get(plan.bankMenuSlot()).item().empty();
    }

    public static UiActionService.GuiSnapshot placeDestination(Minecraft minecraft, DepositPlan plan) {
        UiActionService.GuiSnapshot current = capture(minecraft, plan.before().screenClass());
        if (!sourcePickupMatches(current, plan)) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "The cursor no longer holds the expected inventory item for this Bank page");
        }
        ContainerScreen screen = (ContainerScreen) minecraft.gui.screen();
        Slot destination = screen.getMenu().getSlot(plan.bankMenuSlot());
        if (!destination.getItem().isEmpty() || !destination.mayPlace(screen.getMenu().getCarried())) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "The chosen Bank destination is no longer empty or compatible");
        }
        performPickup(minecraft, current, plan.bankMenuSlot());
        return capture(minecraft, plan.before().screenClass());
    }

    public static boolean depositResultMatches(UiActionService.GuiSnapshot current, DepositPlan plan) {
        if (!sameScreen(current, plan.before()) || !BankWithdrawService.recognizedBankPage(current, plan.bankPage())) return false;
        UiActionService.ItemSnapshot source = current.slots().get(plan.inventoryMenuSlot()).item();
        UiActionService.ItemSnapshot destination = current.slots().get(plan.bankMenuSlot()).item();
        return source.empty() && current.carriedItem().empty() && sameStack(plan.source().item(), destination);
    }

    public static DepositState summary(UiActionService.GuiSnapshot snapshot, DepositPlan plan) {
        if (snapshot == null || plan == null) return null;
        return new DepositState(snapshot.screenClass(), snapshot.syncId(), snapshot.stateId(), snapshot.stateRevision(),
            plan.bankPage(), plan.inventoryMenuSlot(), plan.bankMenuSlot(),
            item(snapshot.slots().get(plan.inventoryMenuSlot()).item()),
            item(snapshot.slots().get(plan.bankMenuSlot()).item()), item(snapshot.carriedItem()));
    }

    private static boolean sameInitialState(UiActionService.GuiSnapshot current, DepositPlan plan) {
        if (!sameScreen(current, plan.before()) || current.stateRevision() != plan.before().stateRevision()
            || !BankWithdrawService.recognizedBankPage(current, plan.bankPage()) || !current.carriedItem().empty()) return false;
        return sameStack(current.slots().get(plan.inventoryMenuSlot()).item(), plan.source().item())
            && sameStack(current.slots().get(plan.bankMenuSlot()).item(), plan.destination().item());
    }

    private static UiActionService.SlotSnapshot playerInventorySlot(UiActionService.GuiSnapshot snapshot, int inventorySlot) {
        return snapshot.slots().stream().filter(slot -> slot.inventorySlot() == inventorySlot).findFirst()
            .filter(slot -> slot.active() && slot.mayPickup())
            .orElseThrow(() -> new UiActionService.UiActionException("UNSAFE_SLOT", "The requested player inventory slot is not active in the Bank screen"));
    }

    private static boolean sameScreen(UiActionService.GuiSnapshot left, UiActionService.GuiSnapshot right) {
        return left.screenIdentity() == right.screenIdentity() && left.syncId() == right.syncId();
    }

    private static boolean sameStack(UiActionService.ItemSnapshot left, UiActionService.ItemSnapshot right) {
        return left.empty() == right.empty() && (left.empty() || (left.itemId().equals(right.itemId())
            && left.components().equals(right.components()) && left.count() == right.count()));
    }

    private static ItemView item(UiActionService.ItemSnapshot item) {
        return item.empty() ? null : new ItemView(item.itemId(), item.name(), item.count());
    }

    private static void performPickup(Minecraft minecraft, UiActionService.GuiSnapshot snapshot, int menuSlot) {
        var player = minecraft.player;
        if (!(minecraft.gui.screen() instanceof ContainerScreen screen)
            || !(screen.getMenu() instanceof ChestMenu menu) || menu.containerId != snapshot.syncId()) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "The Bank screen changed before the pickup interaction");
        }
        minecraft.gameMode.handleContainerInput(snapshot.syncId(), menuSlot, 0, ContainerInput.PICKUP, player);
    }

    private static boolean sameStack(UiActionService.ItemSnapshot expected, UiActionService.ItemSnapshot actual, boolean unused) {
        return sameStack(expected, actual);
    }

    public record DepositPlan(UiActionService.GuiSnapshot before, UiActionService.SlotSnapshot source,
                              UiActionService.SlotSnapshot destination, int inventoryMenuSlot,
                              int bankMenuSlot, int bankPage) {}
    public record DepositStart(DepositPlan plan, UiActionService.GuiSnapshot afterSourcePickup) {}
    public record ItemView(String itemId, String name, int count) {}
    public record DepositState(String screenClass, int syncId, int stateId, long stateRevision,
                               int bankPage, int inventoryMenuSlot, int bankMenuSlot,
                               ItemView inventoryItem, ItemView bankItem, ItemView carriedItem) {}
}
