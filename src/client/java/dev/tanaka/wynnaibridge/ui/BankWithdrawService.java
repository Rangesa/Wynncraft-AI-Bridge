package dev.tanaka.wynnaibridge.ui;

import dev.tanaka.wynnaibridge.state.UiStateRevisionTracker;
import dev.tanaka.wynnaibridge.capture.TextCaptureStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** One single-count item may be withdrawn from any page identified as the Wynncraft Bank. */
public final class BankWithdrawService {
    public static final int MIN_BANK_PAGE = 1;
    public static final int MAX_BANK_PAGE = 100;
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
            throw new UiActionService.UiActionException("NO_GUI", "Open a recognized Wynncraft Bank page first");
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
        validateBankPage(before, bankPage);
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

    public static BankPageStepPlan planPageStep(
        UiActionService.GuiSnapshot before,
        int targetPage,
        String expectedScreen,
        int expectedSyncId,
        long expectedRevision
    ) {
        UiActionService.validateExpectedContext(before, expectedScreen, expectedSyncId, expectedRevision);
        BankPageRecognizer.PageInfo page = recognizeBankPage(before);
        if (!page.recognized() || page.currentPage() == null) {
            throw new UiActionService.UiActionException("UNRECOGNIZED_BANK", "The current screen is not a recognized Wynncraft Bank page");
        }
        if (Math.abs(targetPage - page.currentPage()) != 1) {
            throw new UiActionService.UiActionException("PAGE_STEP_ONLY", "Open one adjacent Bank page per call and use a fresh read before continuing");
        }
        if (page.pageCount() != null && targetPage > page.pageCount()) {
            throw new UiActionService.UiActionException("BANK_PAGE_OUT_OF_RANGE", "The target page exceeds the visible Bank page count");
        }
        int navigationSlot = targetPage > page.currentPage() ? BANK_NEXT_PAGE_SLOT : BANK_PREVIOUS_PAGE_SLOT;
        String expectedLabel = targetPage > page.currentPage()
            ? "Page " + targetPage + " >>>>>" : "Page " + targetPage + " <<<<<";
        UiActionService.SlotSnapshot control = before.slots().get(navigationSlot);
        if (!control.active() || !control.mayPickup() || control.item().empty()
            || !expectedLabel.equals(control.item().name())) {
            throw new UiActionService.UiActionException("BANK_NAVIGATION_NOT_AVAILABLE", "The expected Bank page control is not present and clickable");
        }
        return new BankPageStepPlan(before, page.currentPage(), targetPage, navigationSlot);
    }

    public static UiActionService.GuiSnapshot startPageStep(Minecraft minecraft, BankPageStepPlan plan) {
        UiActionService.GuiSnapshot current = capture(minecraft, plan.before().screenClass());
        if (current.screenIdentity() != plan.before().screenIdentity()
            || current.syncId() != plan.before().syncId()
            || current.stateRevision() != plan.before().stateRevision()
            || !recognizedBankPage(current, plan.currentPage())) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "STATE_CHANGED: Bank screen or page changed before navigation");
        }
        UiActionService.SlotSnapshot control = current.slots().get(plan.navigationSlot());
        String expectedLabel = plan.targetPage() > plan.currentPage()
            ? "Page " + plan.targetPage() + " >>>>>" : "Page " + plan.targetPage() + " <<<<<";
        if (!control.active() || !control.mayPickup() || control.item().empty()
            || !expectedLabel.equals(control.item().name())) {
            throw new UiActionService.UiActionException("STATE_CHANGED", "STATE_CHANGED: the Bank page control changed before navigation");
        }
        performPickup(minecraft, current, plan.navigationSlot());
        return capture(minecraft, plan.before().screenClass());
    }

    public static boolean pageStepMatches(UiActionService.GuiSnapshot after, BankPageStepPlan plan) {
        return after.screenIdentity() == plan.before().screenIdentity()
            && after.syncId() == plan.before().syncId()
            && after.stateId() != plan.before().stateId()
            && recognizedBankPage(after, plan.targetPage());
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
        if (!sameScreen(current, plan.before()) || !recognizedBankPage(current, plan.bankPage())) return false;
        UiActionService.SlotSnapshot source = current.slots().get(plan.bankMenuSlot());
        UiActionService.SlotSnapshot destination = current.slots().get(plan.inventoryMenuSlot());
        return source.item().empty()
            && sameStack(plan.source().item(), current.carriedItem())
            && sameStack(destination.item(), plan.destination().item());
    }

    public static boolean withdrawResultMatches(UiActionService.GuiSnapshot current, BankWithdrawPlan plan) {
        if (!sameScreen(current, plan.before()) || !recognizedBankPage(current, plan.bankPage())) return false;
        UiActionService.SlotSnapshot source = current.slots().get(plan.bankMenuSlot());
        UiActionService.SlotSnapshot destination = current.slots().get(plan.inventoryMenuSlot());
        return source.item().empty()
            && current.carriedItem().empty()
            && sameStack(plan.source().item(), destination.item());
    }

    public static boolean recognizedPage2(UiActionService.GuiSnapshot snapshot) {
        return recognizedBankPage(snapshot, 2);
    }

    public static void validateBankPage2(UiActionService.GuiSnapshot snapshot, int bankPage) {
        validateBankPage(snapshot, bankPage);
    }

    public static BankPageRecognizer.PageInfo recognizeBankPage(UiActionService.GuiSnapshot snapshot) {
        List<BankPageRecognizer.SlotEvidence> evidence = snapshot.slots().stream().map(slot ->
            new BankPageRecognizer.SlotEvidence(slot.menuSlot(), slot.inventorySlot() >= 0
                ? "PLAYER_INVENTORY" : "OPEN_CONTAINER", slot.item().empty() ? null : slot.item().name(), List.of())
        ).toList();
        List<String> captured = TextCaptureStore.INSTANCE.snapshotSince(0L, 1_500L, null, 1000, false).stream()
            .filter(text -> text.source().startsWith("gui.") || text.source().startsWith("tooltip"))
            .map(TextCaptureStore.CapturedText::text).toList();
        return BankPageRecognizer.recognize(snapshot.screenClass(), snapshot.menuClass(), snapshot.title(),
            snapshot.syncId(), snapshot.slots().size(), evidence, captured);
    }

    public static boolean recognizedBankPage(UiActionService.GuiSnapshot snapshot, int expectedPage) {
        BankPageRecognizer.PageInfo page = recognizeBankPage(snapshot);
        return page.recognized() && page.currentPage() != null && page.currentPage() == expectedPage;
    }

    public static void validateBankPage(UiActionService.GuiSnapshot snapshot, int expectedPage) {
        BankPageRecognizer.PageInfo page = recognizeBankPage(snapshot);
        if (expectedPage < MIN_BANK_PAGE || expectedPage > MAX_BANK_PAGE
            || !page.recognized() || page.currentPage() == null || page.currentPage() != expectedPage) {
            throw new UiActionService.UiActionException("UNRECOGNIZED_BANK_PAGE",
                "The current screen or page does not match the requested recognized Wynncraft Bank page");
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
            || !recognizedBankPage(current, plan.bankPage()) || !current.carriedItem().empty()) return false;
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
    public record BankPageStepPlan(UiActionService.GuiSnapshot before, int currentPage, int targetPage, int navigationSlot) {}
    public record ItemView(String itemId, String name, int count) {}
    public record BankState(String screenClass, int syncId, int stateId, long stateRevision,
                            int bankPage, int bankSlot, int inventorySlot,
                            ItemView bankItem, ItemView inventoryItem, ItemView carriedItem) {}
}
