package dev.tanaka.wynnaibridge.ui;

import dev.tanaka.wynnaibridge.state.UiStateRevisionTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** One single-count item may be withdrawn from the observed Wynncraft Bank page 2. */
public final class BankWithdrawService {
    public static final int BANK_PAGE = 2;
    private static final int BANK_CONTENT_SLOT_MIN = 0;
    private static final int BANK_CONTENT_SLOT_MAX = 44;
    private static final int BANK_QUICK_ACTIONS_SLOT = 46;
    private static final int BANK_STORAGE_TYPE_SLOT = 47;
    private static final int BANK_PREVIOUS_PAGE_SLOT = 51;
    private static final int BANK_NEXT_PAGE_SLOT = 52;
    private static final int BANK_MENU_SLOT_COUNT = 90;

    private BankWithdrawService() {}

    public static UiActionService.GuiSnapshot capture(Minecraft minecraft, String expectedScreen) {
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            throw new UiActionService.UiActionException("NOT_CONNECTED", "Not connected to a Minecraft world");
        }
        var activeScreen = minecraft.gui.screen();
        if (activeScreen == null) {
            throw new UiActionService.UiActionException("NO_GUI", "Open the Wynncraft Bank page 2 first");
        }
        String screenClass = activeScreen.getClass().getName();
        if (expectedScreen != null && !expectedScreen.equals(screenClass)) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "STATE_CHANGED: the current screen no longer matches expectedScreen");
        }
        if (!(activeScreen instanceof ContainerScreen containerScreen)
            || !(containerScreen.getMenu() instanceof ChestMenu menu)
            || menu.slots.size() != BANK_MENU_SLOT_COUNT
            || menu.containerId <= 0) {
            throw new UiActionService.UiActionException("UNRECOGNIZED_BANK", "Only the recognized Wynncraft Bank chest screen is supported");
        }

        long revision = UiStateRevisionTracker.INSTANCE.refresh(minecraft);
        List<UiActionService.SlotSnapshot> slots = new ArrayList<>(menu.slots.size());
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.getSlot(index);
            int inventorySlot = slot.container == minecraft.player.getInventory() ? slot.getContainerSlot() : -1;
            slots.add(new UiActionService.SlotSnapshot(
                index,
                inventorySlot,
                slot.isActive(),
                slot.mayPickup(minecraft.player),
                item(slot.getItem())
            ));
        }

        return new UiActionService.GuiSnapshot(
            System.currentTimeMillis(),
            screenClass,
            menu.getClass().getName(),
            containerScreen.getTitle().getString(),
            System.identityHashCode(containerScreen),
            menu.containerId,
            menu.getStateId(),
            revision,
            List.copyOf(slots),
            item(menu.getCarried())
        );
    }

    /** Pure validation entry point, also used by the regression harness. */
    public static BankWithdrawPlan plan(
        UiActionService.GuiSnapshot before,
        int bankPage,
        int bankSlot,
        int inventorySlot,
        String expectedItemId,
        String expectedItemName,
        String expectedScreen,
        int expectedSyncId,
        long expectedRevision
    ) {
        UiActionService.validateExpectedContext(before, expectedScreen, expectedSyncId, expectedRevision);
        validateBankPage2(before, bankPage);
        if (bankSlot < BANK_CONTENT_SLOT_MIN || bankSlot > BANK_CONTENT_SLOT_MAX) {
            throw new UiActionService.UiActionException("UNSAFE_BANK_SLOT", "Only Bank content slots 0 through 44 can be withdrawn");
        }
        if (inventorySlot < 0 || inventorySlot >= 36) {
            throw new UiActionService.UiActionException("UNSAFE_SLOT", "inventorySlot must be between 0 and 35");
        }
        if (!before.carriedItem().empty()) {
            throw new UiActionService.UiActionException("CURSOR_NOT_EMPTY", "The cursor must be empty before withdrawing a Bank item");
        }
        if (expectedItemId == null || expectedItemId.isBlank()) {
            throw new UiActionService.UiActionException("EXPECTED_ITEM_MISMATCH", "expectedItemId is required");
        }

        UiActionService.SlotSnapshot source = before.slots().get(bankSlot);
        if (source.menuSlot() != bankSlot || source.inventorySlot() >= 0) {
            throw new UiActionService.UiActionException("UNSAFE_BANK_SLOT", "The selected slot is not a Bank content slot");
        }
        if (!source.active() || !source.mayPickup()) {
            throw new UiActionService.UiActionException("SLOT_NOT_INTERACTABLE", "The selected Bank slot cannot be picked up");
        }
        UiActionService.validateExpectedItemName(source.item(), expectedItemName);
        if (!source.item().itemId().equals(expectedItemId)) {
            throw new UiActionService.UiActionException("EXPECTED_ITEM_MISMATCH", "expectedItemId does not match the selected Bank slot");
        }
        if (source.item().count() != 1) {
            throw new UiActionService.UiActionException("BANK_STACK_NOT_SUPPORTED", "Only one single-count item can be withdrawn per action");
        }

        UiActionService.SlotSnapshot destination = playerInventorySlot(before, inventorySlot);
        if (!destination.item().empty()) {
            throw new UiActionService.UiActionException("DESTINATION_NOT_EMPTY", "The destination player inventory slot must be empty");
        }
        return new BankWithdrawPlan(before, source, destination, source.menuSlot(), destination.menuSlot(), bankPage);
    }

    /** Revalidates the plan immediately before the first standard pickup interaction. */
    public static BankWithdrawStart start(Minecraft minecraft, BankWithdrawPlan plan) {
        UiActionService.GuiSnapshot current = capture(minecraft, plan.before().screenClass());
        if (!sameInitialState(current, plan)) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "STATE_CHANGED: the Bank slot, destination, cursor, page, or revision changed before withdrawal");
        }
        var screen = (ContainerScreen) minecraft.gui.screen();
        ItemStack sourceStack = screen.getMenu().getSlot(plan.bankMenuSlot()).getItem();
        Slot destination = screen.getMenu().getSlot(plan.inventoryMenuSlot());
        if (!destination.mayPlace(sourceStack)) {
            throw new UiActionService.UiActionException("DESTINATION_NOT_ALLOWED", "The selected player inventory slot cannot accept this item");
        }
        UiActionGate.INSTANCE.consumeBankWithdrawalAllowance();
        performPickup(minecraft, current, plan.bankMenuSlot());
        return new BankWithdrawStart(plan, capture(minecraft, plan.before().screenClass()));
    }

    /** Places the already server-acknowledged item into its one validated empty player slot. */
    public static UiActionService.GuiSnapshot placeDestination(Minecraft minecraft, BankWithdrawPlan plan) {
        UiActionService.GuiSnapshot current = capture(minecraft, plan.before().screenClass());
        if (!sourcePickupMatches(current, plan)) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "STATE_CHANGED: the expected Bank item is no longer on the cursor from its original slot");
        }
        var screen = (ContainerScreen) minecraft.gui.screen();
        Slot destination = screen.getMenu().getSlot(plan.inventoryMenuSlot());
        if (!destination.getItem().isEmpty() || !destination.mayPlace(screen.getMenu().getCarried())) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "STATE_CHANGED: the selected player destination is no longer empty or compatible");
        }
        performPickup(minecraft, current, plan.inventoryMenuSlot());
        return capture(minecraft, plan.before().screenClass());
    }

    public static boolean sourcePickupMatches(UiActionService.GuiSnapshot current, BankWithdrawPlan plan) {
        if (!sameScreen(current, plan.before()) || !recognizedPage2(current)) return false;
        UiActionService.SlotSnapshot source = current.slots().get(plan.bankMenuSlot());
        UiActionService.SlotSnapshot destination = current.slots().get(plan.inventoryMenuSlot());
        return source.item().empty()
            && sameStack(plan.source().item(), current.carriedItem())
            && sameStack(destination.item(), plan.destination().item());
    }

    public static boolean withdrawResultMatches(UiActionService.GuiSnapshot current, BankWithdrawPlan plan) {
        if (!sameScreen(current, plan.before()) || !recognizedPage2(current)) return false;
        UiActionService.SlotSnapshot source = current.slots().get(plan.bankMenuSlot());
        UiActionService.SlotSnapshot destination = current.slots().get(plan.inventoryMenuSlot());
        return source.item().empty()
            && current.carriedItem().empty()
            && sameStack(plan.source().item(), destination.item());
    }

    public static boolean recognizedPage2(UiActionService.GuiSnapshot snapshot) {
        return snapshot.screenClass().equals(ContainerScreen.class.getName())
            && snapshot.menuClass().equals(ChestMenu.class.getName())
            && snapshot.syncId() > 0
            && snapshot.slots().size() == BANK_MENU_SLOT_COUNT
            && "Quick Actions".equals(itemName(snapshot, BANK_QUICK_ACTIONS_SLOT))
            && "Storage Type".equals(itemName(snapshot, BANK_STORAGE_TYPE_SLOT))
            && "Page 1 <<<<<".equals(itemName(snapshot, BANK_PREVIOUS_PAGE_SLOT))
            && "Page 3 >>>>>".equals(itemName(snapshot, BANK_NEXT_PAGE_SLOT));
    }

    public static void validateBankPage2(UiActionService.GuiSnapshot snapshot, int bankPage) {
        if (bankPage != BANK_PAGE || !recognizedPage2(snapshot)) {
            throw new UiActionService.UiActionException("UNRECOGNIZED_BANK_PAGE",
                "The current screen is not the recognized Wynncraft Bank page 2");
        }
    }

    public static BankState summary(UiActionService.GuiSnapshot snapshot, BankWithdrawPlan plan) {
        return new BankState(snapshot.screenClass(), snapshot.syncId(), snapshot.stateId(), snapshot.stateRevision(),
            plan.bankPage(), plan.bankMenuSlot(), plan.inventoryMenuSlot(),
            summaryItem(snapshot.slots().get(plan.bankMenuSlot()).item()),
            summaryItem(snapshot.slots().get(plan.inventoryMenuSlot()).item()),
            summaryItem(snapshot.carriedItem()));
    }

    private static boolean sameInitialState(UiActionService.GuiSnapshot current, BankWithdrawPlan plan) {
        if (!sameScreen(current, plan.before()) || current.stateRevision() != plan.before().stateRevision()
            || !recognizedPage2(current) || !current.carriedItem().empty()) return false;
        return sameStack(current.slots().get(plan.bankMenuSlot()).item(), plan.source().item())
            && sameStack(current.slots().get(plan.inventoryMenuSlot()).item(), plan.destination().item());
    }

    private static UiActionService.SlotSnapshot playerInventorySlot(UiActionService.GuiSnapshot snapshot, int inventorySlot) {
        for (UiActionService.SlotSnapshot slot : snapshot.slots()) {
            if (slot.inventorySlot() == inventorySlot) {
                if (!slot.active() || !slot.mayPickup()) {
                    throw new UiActionService.UiActionException("SLOT_NOT_INTERACTABLE", "The destination player inventory slot is not active");
                }
                return slot;
            }
        }
        throw new UiActionService.UiActionException("UNSAFE_SLOT", "The requested player inventory slot is not present in the Bank screen");
    }

    private static String itemName(UiActionService.GuiSnapshot snapshot, int menuSlot) {
        if (menuSlot < 0 || menuSlot >= snapshot.slots().size()) return null;
        UiActionService.ItemSnapshot item = snapshot.slots().get(menuSlot).item();
        return item.empty() ? null : item.name();
    }

    private static boolean sameScreen(UiActionService.GuiSnapshot left, UiActionService.GuiSnapshot right) {
        return left.screenIdentity() == right.screenIdentity() && left.syncId() == right.syncId();
    }

    private static boolean sameStack(UiActionService.ItemSnapshot left, UiActionService.ItemSnapshot right) {
        return left.empty() == right.empty()
            && (left.empty() || (left.itemId().equals(right.itemId())
                && left.components().equals(right.components())
                && left.count() == right.count()));
    }

    private static ItemView summaryItem(UiActionService.ItemSnapshot item) {
        return item.empty() ? null : new ItemView(item.itemId(), item.name(), item.count());
    }

    private static void performPickup(Minecraft minecraft, UiActionService.GuiSnapshot snapshot, int menuSlot) {
        var player = minecraft.player;
        var screen = minecraft.gui.screen();
        if (!(screen instanceof ContainerScreen containerScreen)
            || !(containerScreen.getMenu() instanceof ChestMenu menu)
            || menu.containerId != snapshot.syncId()) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "STATE_CHANGED: the Bank screen changed before the pickup interaction");
        }
        minecraft.gameMode.handleContainerInput(
            snapshot.syncId(), menuSlot, 0, ContainerInput.PICKUP, player
        );
    }

    private static UiActionService.ItemSnapshot item(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return new UiActionService.ItemSnapshot(true, "", "", 0, "", 0);
        return new UiActionService.ItemSnapshot(
            false,
            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
            stack.getHoverName().getString(),
            stack.getCount(),
            stack.getComponents().toString(),
            stack.getMaxStackSize()
        );
    }

    public record BankWithdrawPlan(
        UiActionService.GuiSnapshot before,
        UiActionService.SlotSnapshot source,
        UiActionService.SlotSnapshot destination,
        int bankMenuSlot,
        int inventoryMenuSlot,
        int bankPage
    ) {}

    public record BankWithdrawStart(BankWithdrawPlan plan, UiActionService.GuiSnapshot afterSourcePickup) {}
    public record ItemView(String itemId, String name, int count) {}
    public record BankState(String screenClass, int syncId, int stateId, long stateRevision,
                            int bankPage, int bankSlot, int inventorySlot,
                            ItemView bankItem, ItemView inventoryItem, ItemView carriedItem) {}
}
