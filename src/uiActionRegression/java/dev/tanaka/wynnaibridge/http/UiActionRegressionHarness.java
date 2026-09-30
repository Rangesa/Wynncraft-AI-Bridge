package dev.tanaka.wynnaibridge.http;

import com.sun.net.httpserver.HttpServer;
import dev.tanaka.wynnaibridge.config.BridgeConfig;
import dev.tanaka.wynnaibridge.ui.BankWithdrawService;
import dev.tanaka.wynnaibridge.ui.BankDepositService;
import dev.tanaka.wynnaibridge.ui.BankPageRecognizer;
import dev.tanaka.wynnaibridge.ui.UiActionGate;
import dev.tanaka.wynnaibridge.ui.UiActionService;
import dev.tanaka.wynnaibridge.ui.WynnSemanticUiService;
import dev.tanaka.wynnaibridge.ui.WynnAbilitySelectionService;
import dev.tanaka.wynnaibridge.knowledge.WynnAbilityDataService;
import dev.tanaka.wynnaibridge.state.AbilityTreeDebugCollector;
import dev.tanaka.wynnaibridge.state.AbilityTreeSemanticCollector;
import dev.tanaka.wynnaibridge.state.ItemInspector;
import dev.tanaka.wynnaibridge.state.OpenContainerCollector;
import dev.tanaka.wynnaibridge.capture.TextCaptureStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic gate, stale-state, tool-list, and local MCP authorization regression checks. */
public final class UiActionRegressionHarness {
    private static int assertions;

    private UiActionRegressionHarness() {}

    public static void main(String[] args) throws Exception {
        gateStartsDisarmedAndRequiresLocalArm();
        gateExpiresSerializesRateLimitsAndDeduplicates();
        abilityAllowanceIsChargedOnlyImmediatelyBeforeClick();
        staleScreenSyncAndRevisionAreRejected();
        itemAndStackValidationRejectUnsafeExpectedState();
        abilityTreeCorrelationKeepsAmbiguousRuntimeStateUnknown();
        officialPlayerAbilityMapsAreFlattenedFromPageGroups();
        characterInfoAndSkillValidationFailClosed();
        bankWithdrawSupportsAnyRecognizedPageAndOneEmptyInventorySlot();
        bankDepositUsesAnInternalEmptySlotAndRejectsUnsafeState();
        toolListsKeepTheExistingFifteenReadTools();
        abilitySelectionPreflightRejectsUnknownOrUnmetState();
        modMenuSettingsPersistAndDisarmSafely();
        publicMcpKeepsTheExistingBearerGateAndRequestGuards();
        publicUiToolsRequireLocalArm();
        System.out.println("UI action regression checks passed: " + assertions);
    }

    private static void gateStartsDisarmedAndRequiresLocalArm() {
        FakeClock clock = new FakeClock();
        UiActionGate gate = clock.newGate();
        check(!gate.status().armed(), "a new UI action gate must start disarmed");
        expectGate("UI_ACTIONS_DISARMED", "UI actions are not locally armed",
            () -> gate.beginAction("unarmed", new Object(), true));

        Object world = new Object();
        gate.arm(world, 30);
        check(gate.status().armed(), "local arm should enable the in-memory gate");
        check(gate.status().category() == UiActionGate.Category.INVENTORY
                && gate.status().categoryActionsRemaining() == 1,
            "the legacy no-argument arm must authorize exactly one Inventory action");
        check(gate.status().bankWithdrawalsRemaining() == 0,
            "ordinary local arm must not authorize Bank withdrawals");
        gate.beginAction("active#1", world, true);
        expectGate("WRONG_ARM_CATEGORY", null, gate::consumeBankWithdrawalAllowance);
        gate.requireActionActive(world, true);
        gate.disarm();
        check(!gate.status().armed(), "local disarm should clear the in-memory gate");
        check(gate.status().bankWithdrawalsRemaining() == 0,
            "local disarm must clear any Bank withdrawal allowance");
        expectGate("UI_ACTIONS_DISARMED", "UI actions are not locally armed",
            () -> gate.requireActionActive(world, true));
        gate.endAction();

        gate.arm(world, 30, UiActionGate.Category.BANK, 2);
        check(gate.status().bankWithdrawalsRemaining() == 2,
            "a client-only Bank arm may authorize at most the explicitly requested number of withdrawals");
        gate.beginAction("bank#1", world, true, UiActionGate.Category.BANK, 1);
        check(gate.status().bankWithdrawalsRemaining() == 1 && gate.consumeBankWithdrawalAllowance() == 1,
            "a Bank action must reserve exactly one local allowance before the first click");
        gate.endAction();
        clock.advanceMillis(750L);
        gate.beginAction("bank#2", world, true, UiActionGate.Category.BANK, 1);
        gate.endAction();
        clock.advanceMillis(750L);
        expectGate("ACTION_ALLOWANCE_EXHAUSTED", null,
            () -> gate.beginAction("bank#3", world, true, UiActionGate.Category.BANK, 1));
        expectGate("INVALID_ACTION_ALLOWANCE", null,
            () -> gate.arm(world, 30, UiActionGate.Category.BANK, 8));
        gate.arm(world, 30, UiActionGate.Category.SKILLS, 2);
        gate.beginAction("shared-request-id", world, true, UiActionGate.Category.SKILLS, 1);
        gate.endAction();
        clock.advanceMillis(750L);
        expectGate("DUPLICATE_ACTION", null, () ->
            gate.beginAction("shared-request-id", world, true, UiActionGate.Category.BANK, 1));
        expectGate("WRONG_ARM_CATEGORY", null,
            () -> gate.beginAction("inventory-under-skills", world, true, UiActionGate.Category.INVENTORY, 1));
    }

    private static void gateExpiresSerializesRateLimitsAndDeduplicates() {
        FakeClock clock = new FakeClock();
        UiActionGate gate = clock.newGate();
        Object world = new Object();
        gate.arm(world, 1, UiActionGate.Category.INVENTORY, 10);
        gate.beginAction("click#1", world, true);
        expectGate("ACTION_IN_PROGRESS", null, () -> gate.beginAction("move#2", world, true));
        gate.endAction();
        expectGate("RATE_LIMITED", null, () -> gate.beginAction("click#2", world, true));

        clock.advanceMillis(750L);
        gate.beginAction("click#2", world, true);
        gate.endAction();
        expectGate("DUPLICATE_ACTION", null, () -> gate.beginAction("click#2", world, true));

        gate.observeWorld(new Object(), true);
        check(!gate.status().armed(), "a world identity change must disarm UI actions");
        gate.arm(world, 1);
        gate.observeWorld(world, false);
        check(!gate.status().armed(), "disconnect must disarm UI actions");

        gate.arm(world, 1);
        clock.advanceMillis(1000L);
        check(!gate.status().armed(), "arm expiry must disarm UI actions");
        expectGate("UI_ACTIONS_DISARMED", "UI actions are not locally armed",
            () -> gate.beginAction("expired", world, true));
    }

    private static void abilityAllowanceIsChargedOnlyImmediatelyBeforeClick() {
        FakeClock clock = new FakeClock();
        UiActionGate gate = clock.newGate();
        Object world = new Object();
        gate.arm(world, 30, UiActionGate.Category.ABILITY, 2);
        gate.beginActionDeferredCharge("ability-preflight-refused", world, true, UiActionGate.Category.ABILITY, 1);
        check(gate.status().categoryActionsRemaining() == 2,
            "an ability preflight refusal must not spend its node allowance");
        gate.endAction();
        clock.advanceMillis(750L);
        gate.beginActionDeferredCharge("ability-click-sent", world, true, UiActionGate.Category.ABILITY, 1);
        check(gate.chargeAllowanceBeforeClick(world, true, 1) == 1,
            "the ability allowance must be spent immediately before its one click");
        gate.endAction();
        clock.advanceMillis(750L);
        gate.beginActionDeferredCharge("ability-result-unknown", world, true, UiActionGate.Category.ABILITY, 1);
        gate.chargeAllowanceBeforeClick(world, true, 1);
        gate.endAction();
        check(gate.status().categoryActionsRemaining() == 0,
            "an attempted ability click consumes one allowance even if its result is later unknown");
    }

    private static void staleScreenSyncAndRevisionAreRejected() {
        UiActionService.GuiSnapshot current = new UiActionService.GuiSnapshot(
            1L, "InventoryScreen", "InventoryMenu", "Inventory", 5, 0, 9, 44L, List.of(),
            new UiActionService.ItemSnapshot(true, "", "", 0, "", 0)
        );
        UiActionService.validateExpectedContext(current, "InventoryScreen", 0, 44L);
        expectUiCode("STATE_CHANGED", () -> UiActionService.validateExpectedContext(current, "ChestScreen", 0, 44L));
        expectUiCode("STATE_CHANGED", () -> UiActionService.validateExpectedContext(current, "InventoryScreen", 1, 44L));
        expectUiCode("STATE_CHANGED", () -> UiActionService.validateExpectedContext(current, "InventoryScreen", 0, 43L));
    }

    private static void itemAndStackValidationRejectUnsafeExpectedState() {
        UiActionService.ItemSnapshot source = new UiActionService.ItemSnapshot(false,
            "minecraft:stone", "Test Stone", 16, "{custom_name=none}", 64);
        UiActionService.ItemSnapshot compatible = new UiActionService.ItemSnapshot(false,
            "minecraft:stone", "Test Stone", 32, "{custom_name=none}", 64);
        UiActionService.ItemSnapshot different = new UiActionService.ItemSnapshot(false,
            "minecraft:dirt", "Test Dirt", 1, "{}", 64);
        UiActionService.ItemSnapshot insufficient = new UiActionService.ItemSnapshot(false,
            "minecraft:stone", "Test Stone", 60, "{custom_name=none}", 64);
        UiActionService.ItemSnapshot empty = new UiActionService.ItemSnapshot(true, "", "", 0, "", 0);

        UiActionService.validateExpectedItemName(source, "Test Stone");
        expectUiCode("EXPECTED_ITEM_MISMATCH", () -> UiActionService.validateExpectedItemName(source, "Other"));
        UiActionService.validateMoveDestination(source, empty);
        UiActionService.validateMoveDestination(source, compatible);
        expectUiCode("UNSAFE_DESTINATION", () -> UiActionService.validateMoveDestination(source, different));
        expectUiCode("STACK_WOULD_SPLIT", () -> UiActionService.validateMoveDestination(source, insufficient));
    }

    private static void bankWithdrawSupportsAnyRecognizedPageAndOneEmptyInventorySlot() {
        UiActionService.GuiSnapshot before = bankPageTwoSnapshot();
        BankWithdrawService.BankWithdrawPlan plan = BankWithdrawService.plan(before, 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L);
        check(plan.bankMenuSlot() == 10 && plan.inventoryMenuSlot() == 59,
            "Bank withdrawals must map the chosen player inventory index to its actual ChestMenu slot");
        check(BankWithdrawService.recognizedPage2(before),
            "the Wynncraft Bank page 2 marker layout should be recognized");

        expectUiCode("UNRECOGNIZED_BANK_PAGE", () -> BankWithdrawService.plan(before, 1, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L));
        expectUiCode("UNSAFE_BANK_SLOT", () -> BankWithdrawService.plan(before, 2, 51, 14,
            "minecraft:paper", "Page 1 <<<<<", before.screenClass(), 9, 165L));
        expectUiCode("STATE_CHANGED", () -> BankWithdrawService.plan(before, 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 8, 165L));
        expectUiCode("STATE_CHANGED", () -> BankWithdrawService.plan(before, 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 164L));
        expectUiCode("EXPECTED_ITEM_MISMATCH", () -> BankWithdrawService.plan(before, 2, 10, 14,
            "minecraft:stone", "Test Helmet", before.screenClass(), 9, 165L));

        List<UiActionService.SlotSnapshot> wrongPageSlots = new ArrayList<>(before.slots());
        wrongPageSlots.set(52, slot(52, -1, "minecraft:paper", "Page 4 >>>>>", 1));
        expectUiCode("UNRECOGNIZED_BANK_PAGE", () -> BankWithdrawService.plan(
            snapshot(before, wrongPageSlots, before.carriedItem()), 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L));

        List<UiActionService.SlotSnapshot> nonBankSlots = new ArrayList<>(before.slots());
        nonBankSlots.set(47, slot(47, -1, "minecraft:paper", "Merchant", 1));
        expectUiCode("UNRECOGNIZED_BANK_PAGE", () -> BankWithdrawService.plan(
            snapshot(before, nonBankSlots, before.carriedItem()), 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L));

        List<UiActionService.SlotSnapshot> stackedSource = new ArrayList<>(before.slots());
        stackedSource.set(10, slot(10, -1, "minecraft:leather_helmet", "Test Helmet", 2));
        expectUiCode("BANK_STACK_NOT_SUPPORTED", () -> BankWithdrawService.plan(
            snapshot(before, stackedSource, before.carriedItem()), 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L));

        List<UiActionService.SlotSnapshot> occupiedDestination = new ArrayList<>(before.slots());
        occupiedDestination.set(59, slot(59, 14, "minecraft:stone", "Occupied", 1));
        expectUiCode("DESTINATION_NOT_EMPTY", () -> BankWithdrawService.plan(
            snapshot(before, occupiedDestination, before.carriedItem()), 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L));

        UiActionService.ItemSnapshot cursor = new UiActionService.ItemSnapshot(false,
            "minecraft:stone", "Carried", 1, "{}", 64);
        expectUiCode("CURSOR_NOT_EMPTY", () -> BankWithdrawService.plan(
            snapshot(before, before.slots(), cursor), 2, 10, 14,
            "minecraft:leather_helmet", "Test Helmet", before.screenClass(), 9, 165L));

        for (int page : List.of(1, 2, 3, 12)) {
            UiActionService.GuiSnapshot recognized = bankPageSnapshot(page, 12);
            BankPageRecognizer.PageInfo pageInfo = BankWithdrawService.recognizeBankPage(recognized);
            check(pageInfo.recognized() && pageInfo.currentPage() == page && pageInfo.pageCount() == 12,
                "Bank pages 1, 2, 3+, and the last page should be recognized from current-page controls");
            BankWithdrawService.plan(recognized, page, 10, 14,
                "minecraft:leather_helmet", "Test Helmet", recognized.screenClass(), 9, 165L);
        }

        UiActionService.GuiSnapshot ambiguousFirst = bankPageSnapshot(1, 12);
        ambiguousFirst = withTitle(ambiguousFirst, "");
        check(!BankWithdrawService.recognizeBankPage(ambiguousFirst).recognized(),
            "a single next-page label without explicit current-page text must not guess the first page");
        UiActionService.GuiSnapshot ambiguousLast = bankPageSnapshot(12, 12);
        ambiguousLast = withTitle(ambiguousLast, "");
        check(!BankWithdrawService.recognizeBankPage(ambiguousLast).recognized(),
            "a single previous-page label without explicit current-page text must not guess the last page");

        UiActionService.GuiSnapshot pageTwo = bankPageSnapshot(2, 12);
        BankWithdrawService.BankPageStepPlan step = BankWithdrawService.planPageStep(
            pageTwo, 3, pageTwo.screenClass(), pageTwo.syncId(), pageTwo.stateRevision());
        check(step.currentPage() == 2 && step.targetPage() == 3 && step.navigationSlot() == 52,
            "semantic page navigation must resolve the current next-page control internally");
        expectUiCode("PAGE_STEP_ONLY", () -> BankWithdrawService.planPageStep(
            pageTwo, 4, pageTwo.screenClass(), pageTwo.syncId(), pageTwo.stateRevision()));
        expectUiCode("STATE_CHANGED", () -> BankWithdrawService.planPageStep(
            pageTwo, 3, pageTwo.screenClass(), pageTwo.syncId() + 1, pageTwo.stateRevision()));

        UiActionService.GuiSnapshot notBank = snapshot(pageTwo, pageTwo.slots(), pageTwo.carriedItem());
        notBank = new UiActionService.GuiSnapshot(notBank.capturedAt(), "MerchantScreen", notBank.menuClass(),
            notBank.title(), notBank.screenIdentity(), notBank.syncId(), notBank.stateId(), notBank.stateRevision(),
            notBank.slots(), notBank.carriedItem());
        check(!BankWithdrawService.recognizeBankPage(notBank).recognized(),
            "a generic chest-like screen must not be recognized as the Bank");
    }

    private static void bankDepositUsesAnInternalEmptySlotAndRejectsUnsafeState() {
        UiActionService.GuiSnapshot original = bankPageTwoSnapshot();
        List<UiActionService.SlotSnapshot> slots = new ArrayList<>(original.slots());
        slots.set(59, slot(59, 14, "minecraft:emerald", "Test Emerald", 1));
        UiActionService.GuiSnapshot before = snapshot(original, slots, original.carriedItem());
        BankDepositService.DepositPlan plan = BankDepositService.plan(before, 2, 14,
            "minecraft:emerald", "Test Emerald", before.screenClass(), before.syncId(), before.stateRevision());
        check(plan.inventoryMenuSlot() == 59 && plan.bankMenuSlot() == 0,
            "Bank deposit must resolve the player menu slot and first empty Bank slot internally");

        List<UiActionService.SlotSnapshot> afterPickupSlots = new ArrayList<>(before.slots());
        afterPickupSlots.set(59, emptySlot(59, 14));
        UiActionService.GuiSnapshot afterPickup = new UiActionService.GuiSnapshot(before.capturedAt(), before.screenClass(),
            before.menuClass(), before.title(), before.screenIdentity(), before.syncId(), before.stateId() + 1,
            before.stateRevision() + 1, List.copyOf(afterPickupSlots), plan.source().item());
        check(BankDepositService.sourcePickupMatches(afterPickup, plan),
            "the first deposit click must be acknowledged as source-empty with the exact item on the cursor");

        List<UiActionService.SlotSnapshot> afterDepositSlots = new ArrayList<>(afterPickupSlots);
        afterDepositSlots.set(0, slot(0, -1, "minecraft:emerald", "Test Emerald", 1));
        UiActionService.GuiSnapshot afterDeposit = new UiActionService.GuiSnapshot(before.capturedAt(), before.screenClass(),
            before.menuClass(), before.title(), before.screenIdentity(), before.syncId(), before.stateId() + 2,
            before.stateRevision() + 2, List.copyOf(afterDepositSlots), before.carriedItem());
        check(BankDepositService.depositResultMatches(afterDeposit, plan),
            "deposit success must require the exact item in Bank, empty player source, empty cursor, and the same page");

        expectUiCode("UNRECOGNIZED_BANK_PAGE", () -> BankDepositService.plan(before, 1, 14,
            "minecraft:emerald", "Test Emerald", before.screenClass(), before.syncId(), before.stateRevision()));
        expectUiCode("STATE_CHANGED", () -> BankDepositService.plan(before, 2, 14,
            "minecraft:emerald", "Test Emerald", before.screenClass(), before.syncId() + 1, before.stateRevision()));
        expectUiCode("STATE_CHANGED", () -> BankDepositService.plan(before, 2, 14,
            "minecraft:emerald", "Test Emerald", before.screenClass(), before.syncId(), before.stateRevision() + 1));
        expectUiCode("EXPECTED_ITEM_MISMATCH", () -> BankDepositService.plan(before, 2, 14,
            "minecraft:stone", "Test Emerald", before.screenClass(), before.syncId(), before.stateRevision()));
        expectUiCode("BANK_STACK_NOT_SUPPORTED", () -> {
            List<UiActionService.SlotSnapshot> stacked = new ArrayList<>(before.slots());
            stacked.set(59, slot(59, 14, "minecraft:emerald", "Test Emerald", 2));
            BankDepositService.plan(snapshot(before, stacked, before.carriedItem()), 2, 14,
                "minecraft:emerald", "Test Emerald", before.screenClass(), before.syncId(), before.stateRevision());
        });
        UiActionService.GuiSnapshot cursorHeld = snapshot(before, before.slots(),
            new UiActionService.ItemSnapshot(false, "minecraft:stone", "Cursor item", 1, "{}", 64));
        expectUiCode("CURSOR_NOT_EMPTY", () -> BankDepositService.plan(cursorHeld, 2, 14,
            "minecraft:emerald", "Test Emerald", before.screenClass(), before.syncId(), before.stateRevision()));
        List<UiActionService.SlotSnapshot> fullBankSlots = new ArrayList<>(before.slots());
        for (int i = 0; i <= 44; i++) if (fullBankSlots.get(i).item().empty()) fullBankSlots.set(i, slot(i, -1, "minecraft:stone", "Filler", 1));
        UiActionService.GuiSnapshot fullBank = snapshot(before, fullBankSlots, before.carriedItem());
        expectUiCode("NO_EMPTY_BANK_SLOT", () -> BankDepositService.plan(fullBank, 2, 14,
            "minecraft:emerald", "Test Emerald", before.screenClass(), before.syncId(), before.stateRevision()));

        UiActionService.GuiSnapshot wrongScreen = new UiActionService.GuiSnapshot(before.capturedAt(), "MerchantScreen",
            before.menuClass(), before.title(), before.screenIdentity(), before.syncId(), before.stateId(), before.stateRevision(),
            before.slots(), before.carriedItem());
        expectUiCode("UNRECOGNIZED_BANK_PAGE", () -> BankDepositService.plan(wrongScreen, 2, 14,
            "minecraft:emerald", "Test Emerald", wrongScreen.screenClass(), wrongScreen.syncId(), wrongScreen.stateRevision()));
    }

    private static void toolListsKeepTheExistingFifteenReadTools() {
        Map<String, Object> readOnly = listTools(false, false);
        List<Map<String, Object>> readTools = tools(readOnly);
        check(readTools.size() == 20, "default mode must retain the 15 existing tools and add five read-only Wynncraft inspection tools");
        Set<String> expected = Set.of(
            "minecraft_get_state", "minecraft_get_recent_text", "minecraft_get_slot_tooltip",
            "minecraft_get_context", "minecraft_get_open_container", "minecraft_get_inventory",
            "minecraft_get_visible_ui", "wynn_inspect_hovered_item", "wynn_knowledge_search",
            "wynn_search_items", "wynn_get_item", "wynn_search_locations", "wynn_search_wiki",
            "wynn_get_wiki_page", "wynn_index_status", "wynn_get_official_ability_tree",
            "wynn_get_class_info", "wynn_get_player_abilities", "wynn_get_skill_points", "wynn_get_ability_tree"
        );
        check(names(readTools).equals(expected), "default tool names must remain identical");
        check(readTools.stream().noneMatch(t -> String.valueOf(t.get("name")).startsWith("minecraft_send_")),
            "allowActions=false must keep command and chat hidden");
        check(readTools.stream().noneMatch(t -> String.valueOf(t.get("name")).contains("gui_slot")
                || String.valueOf(t.get("name")).contains("move_inventory")),
            "allowUiActions=false must hide UI write tools");
        check(names(readTools).contains("wynn_get_ability_tree"),
            "read-only Ability Tree runtime diagnostics must remain available when UI writes are disabled");

        List<Map<String, Object>> uiEnabled = tools(listTools(false, true));
        check(uiEnabled.size() == 30, "allowUiActions=true must add ten category-armed semantic tools to the 20 read tools");
        check(names(uiEnabled).containsAll(Set.of("minecraft_click_gui_slot", "minecraft_move_inventory_item", "minecraft_equip_item", "minecraft_withdraw_bank_item", "wynn_open_bank_page",
                "wynn_deposit_bank_item", "wynn_select_ability", "wynn_get_skill_points", "wynn_open_character_info", "wynn_assign_skill_points", "wynn_open_ability_tree")),
            "all UI write tools must be listed independently of allowActions");
        check(names(uiEnabled).contains("wynn_get_ability_tree"),
            "the read-only runtime Ability Tree diagnostic must remain available with UI tooling enabled");
        check(!names(uiEnabled).contains("minecraft_send_command") && !names(uiEnabled).contains("minecraft_send_chat"),
            "UI actions must not enable legacy command or chat actions");
        check(annotation(uiEnabled, "minecraft_click_gui_slot").equals(Map.of(
                "readOnlyHint", false, "destructiveHint", true, "idempotentHint", false, "openWorldHint", false)),
            "slot click annotations must describe a non-idempotent destructive write");
        check(annotation(uiEnabled, "minecraft_move_inventory_item").equals(Map.of(
                "readOnlyHint", false, "destructiveHint", false, "idempotentHint", false, "openWorldHint", false)),
            "inventory move annotations must describe a non-destructive non-idempotent write");
        check(annotation(uiEnabled, "minecraft_equip_item").equals(Map.of(
                "readOnlyHint", false, "destructiveHint", false, "idempotentHint", false, "openWorldHint", false)),
            "equipment changes must describe a non-destructive non-idempotent write");
        check(annotation(uiEnabled, "minecraft_withdraw_bank_item").equals(Map.of(
                "readOnlyHint", false, "destructiveHint", false, "idempotentHint", false, "openWorldHint", false)),
            "bounded Bank withdrawal must describe a non-destructive non-idempotent write");
        check(annotation(uiEnabled, "wynn_deposit_bank_item").equals(Map.of(
                "readOnlyHint", false, "destructiveHint", false, "idempotentHint", false, "openWorldHint", false)),
            "Bank deposit must describe a bounded non-destructive non-idempotent write");
        check(annotation(uiEnabled, "wynn_select_ability").equals(Map.of(
                "readOnlyHint", false, "destructiveHint", true, "idempotentHint", false, "openWorldHint", false)),
            "Ability selection must be annotated as a one-node progression change");
        check(annotation(uiEnabled, "wynn_get_skill_points").get("readOnlyHint").equals(true),
            "skill-point inspection must remain read-only");
        check(annotation(uiEnabled, "wynn_assign_skill_points").equals(Map.of(
                "readOnlyHint", false, "destructiveHint", true, "idempotentHint", false, "openWorldHint", false)),
            "skill-point assignment must be annotated as a non-idempotent progression change");

        List<Map<String, Object>> legacyWrites = tools(listTools(true, false));
        check(legacyWrites.size() == 22 && names(legacyWrites).containsAll(Set.of("minecraft_send_command", "minecraft_send_chat")),
            "allowActions=true must preserve the two legacy action tools");
        check(annotation(legacyWrites, "minecraft_send_command").equals(Map.of(
                "readOnlyHint", false, "destructiveHint", true, "idempotentHint", false, "openWorldHint", true)),
            "legacy command annotations must remain unchanged");
        check(names(legacyWrites).stream().noneMatch(name -> name.startsWith("minecraft_click_gui")),
            "legacy allowActions must not turn on UI actions");

        List<Map<String, Object>> bothEnabled = tools(listTools(true, true));
        check(bothEnabled.size() == 32 && names(bothEnabled).containsAll(Set.of(
                "minecraft_send_command", "minecraft_send_chat", "wynn_deposit_bank_item", "wynn_select_ability")),
            "both independent flags enabled must expose 20 read + 10 UI write + 2 Bearer action tools");
        check(McpEndpoint.toolCountForConfig(config(8765, false, "", false)) == 20
                && McpEndpoint.toolCountForConfig(config(8765, true, "token", false)) == 22
                && McpEndpoint.toolCountForConfig(config(8765, false, "", true)) == 30
                && McpEndpoint.toolCountForConfig(config(8765, true, "token", true)) == 32,
            "Mod Menu tool count must match each allowActions/allowUiActions combination");
    }

    private static void publicMcpKeepsTheExistingBearerGateAndRequestGuards() throws Exception {
        int noAuthPort = freeLoopbackPort();
        try (BridgeHttpServer server = new BridgeHttpServer(config(noAuthPort, false, "", false))) {
            server.start();
            HttpResponse list = request(noAuthPort, "127.0.0.1:" + noAuthPort, null, null, listRequest());
            check(list.status() == 200, "read-only MCP list should remain unauthenticated when no token is configured");
            check(toolNames(list.body()).size() == 20, "live MCP tools/list should preserve the 15 existing tools and include new read tools");
            HttpResponse hiddenAction = request(noAuthPort, "127.0.0.1:" + noAuthPort, null, null, disabledClickRequest());
            check(hiddenAction.status() == 200 && hiddenAction.body().contains("UI_ACTIONS_DISABLED"),
                "a direct tools/call must still reject UI writes while allowUiActions=false");
            HttpResponse hiddenBank = request(noAuthPort, "127.0.0.1:" + noAuthPort, null, null, bankWithdrawRequest());
            check(hiddenBank.status() == 200 && hiddenBank.body().contains("UI_ACTIONS_DISABLED"),
                "a direct Bank withdrawal call must reject while allowUiActions=false");
        }

        int authPort = freeLoopbackPort();
        String token = "regression-test-token";
        try (BridgeHttpServer server = new BridgeHttpServer(config(authPort, true, token, true))) {
            server.start();
            HttpResponse missingToken = request(authPort, "127.0.0.1:" + authPort, null, null, listRequest());
            check(missingToken.status() == 401, "allowActions=true must require Bearer authorization for /mcp");
            HttpResponse validToken = request(authPort, "127.0.0.1:" + authPort, null, "Bearer " + token, listRequest());
            check(validToken.status() == 200, "the configured Bearer token should pass the unchanged MCP auth gate");
            Set<String> names = toolNames(validToken.body());
            check(names.containsAll(Set.of("minecraft_send_command", "minecraft_send_chat")),
                "the authorized legacy MCP list should still expose command/chat");
            check(names.containsAll(Set.of("minecraft_click_gui_slot", "minecraft_move_inventory_item", "minecraft_equip_item", "minecraft_withdraw_bank_item", "wynn_get_ability_tree")),
                "UI tools must remain behind the unchanged global MCP Bearer gate");

            HttpResponse badHost = request(authPort, "attacker.invalid", null, "Bearer " + token, listRequest());
            check(badHost.status() == 403, "invalid Host must continue to be rejected before MCP processing");
            HttpResponse origin = request(authPort, "127.0.0.1:" + authPort, "Origin: https://example.invalid\r\n", "Bearer " + token, listRequest());
            check(origin.status() == 403, "Origin-bearing browser requests must continue to be rejected");
        }
    }

    private static void publicUiToolsRequireLocalArm() throws Exception {
        UiActionGate.INSTANCE.disarm();
        int port = freeLoopbackPort();
        try (BridgeHttpServer server = new BridgeHttpServer(config(port, false, "", true))) {
            server.start();
            HttpResponse list = request(port, "127.0.0.1:" + port, null, null, listRequest());
            check(list.status() == 200 && toolNames(list.body()).size() == 30,
                "allowUiActions=true should list the UI tools through the existing no-auth MCP connection");
            HttpResponse disarmed = request(port, "127.0.0.1:" + port, null, null, clickRequest());
            check(disarmed.status() == 200 && disarmed.body().contains("UI actions are not locally armed"),
                "public no-auth UI writes must fail closed before the local arm command");
            HttpResponse disarmedBank = request(port, "127.0.0.1:" + port, null, null, bankWithdrawRequest());
            check(disarmedBank.status() == 200 && disarmedBank.body().contains("UI actions are not locally armed"),
                "Bank withdrawals must also fail closed before the explicit local arm command");
            HttpResponse disarmedSkill = request(port, "127.0.0.1:" + port, null, null, openCharacterInfoRequest());
            check(disarmedSkill.status() == 200 && disarmedSkill.body().contains("UI actions are not locally armed"),
                "semantic Character Info actions must also fail closed before local arm");
            HttpResponse disarmedDeposit = request(port, "127.0.0.1:" + port, null, null, bankDepositRequest());
            check(disarmedDeposit.status() == 200 && disarmedDeposit.body().contains("UI actions are not locally armed"),
                "public no-auth Bank deposit must fail closed before local bank arm");
            HttpResponse disarmedAbility = request(port, "127.0.0.1:" + port, null, null, abilitySelectionRequest());
            check(disarmedAbility.status() == 200 && disarmedAbility.body().contains("UI actions are not locally armed"),
                "public no-auth Ability selection must fail closed before local ability arm");

            var healthResponse = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/health"))
                    .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()
            );
            Map<String, Object> health = Json.object(Json.parse(healthResponse.body()));
            check(healthResponse.statusCode() == 200
                    && Boolean.TRUE.equals(health.get("uiActionsEnabled"))
                    && Boolean.FALSE.equals(health.get("uiActionsArmed"))
                    && Long.valueOf(0L).equals(health.get("uiActionsArmExpiresAt"))
                    && Long.valueOf(0L).equals(health.get("uiActionsBankWithdrawalsRemaining")),
                "health may report UI enable/arm state without revealing secrets");
            check(!health.containsKey("token"), "health must not expose an authentication secret");
        }
    }

    private static BridgeConfig config(int port, boolean allowActions, String token, boolean allowUiActions) {
        return new BridgeConfig(port, "", allowActions, token, false, allowUiActions, 300,
            false, false, 60, 12);
    }

    private static Map<String, Object> listTools(boolean allowActions, boolean allowUiActions) {
        int port = 8765;
        return new McpEndpoint(config(port, allowActions, allowActions ? "test-token" : "", allowUiActions))
            .listToolsResult(false);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tools(Map<String, Object> result) {
        return (List<Map<String, Object>>) (List<?>) result.get("tools");
    }

    private static Set<String> names(List<Map<String, Object>> tools) {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> tool : tools) names.add((String) tool.get("name"));
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> annotation(List<Map<String, Object>> tools, String name) {
        for (Map<String, Object> tool : tools) {
            if (name.equals(tool.get("name"))) return (Map<String, Object>) tool.get("annotations");
        }
        throw new AssertionError("Missing tool " + name);
    }

    private static byte[] listRequest() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] disabledClickRequest() {
        return ("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{" +
            "\"name\":\"minecraft_click_gui_slot\",\"arguments\":{" +
            "\"slot\":1,\"button\":\"left\",\"expectedItemName\":\"test\"," +
            "\"expectedScreen\":\"InventoryScreen\",\"expectedSyncId\":0,\"expectedRevision\":1}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] clickRequest() {
        return ("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{" +
            "\"name\":\"minecraft_click_gui_slot\",\"arguments\":{" +
            "\"slot\":1,\"button\":\"left\",\"expectedItemName\":\"test\"," +
            "\"expectedScreen\":\"InventoryScreen\",\"expectedSyncId\":0,\"expectedRevision\":1}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] abilityTreeRequest() {
        return ("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{" +
            "\"name\":\"wynn_get_ability_tree\",\"arguments\":{}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bankWithdrawRequest() {
        return ("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{" +
            "\"name\":\"minecraft_withdraw_bank_item\",\"arguments\":{" +
            "\"bankSlot\":10,\"inventorySlot\":14,\"expectedItemId\":\"minecraft:leather_helmet\"," +
            "\"expectedItemName\":\"Test Helmet\",\"expectedBankPage\":2," +
            "\"expectedScreen\":\"net.minecraft.client.gui.screens.inventory.ContainerScreen\"," +
            "\"expectedSyncId\":9,\"expectedRevision\":165}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static UiActionService.GuiSnapshot bankPageTwoSnapshot() {
        List<UiActionService.SlotSnapshot> slots = new ArrayList<>(90);
        for (int menuSlot = 0; menuSlot < 90; menuSlot++) {
            int inventorySlot = menuSlot < 54 ? -1 : menuSlot < 81 ? menuSlot - 45 : menuSlot - 81;
            slots.add(new UiActionService.SlotSnapshot(menuSlot, inventorySlot, true, true,
                new UiActionService.ItemSnapshot(true, "", "", 0, "", 0)));
        }
        slots.set(10, slot(10, -1, "minecraft:leather_helmet", "Test Helmet", 1));
        slots.set(46, slot(46, -1, "minecraft:paper", "Quick Actions", 1));
        slots.set(47, slot(47, -1, "minecraft:paper", "Storage Type", 1));
        slots.set(51, slot(51, -1, "minecraft:paper", "Page 1 <<<<<", 1));
        slots.set(52, slot(52, -1, "minecraft:paper", "Page 3 >>>>>", 1));
        return new UiActionService.GuiSnapshot(1L,
            "net.minecraft.client.gui.screens.inventory.ContainerScreen",
            "net.minecraft.world.inventory.ChestMenu", "", 5, 9, 48, 165L, List.copyOf(slots),
            new UiActionService.ItemSnapshot(true, "", "", 0, "", 0));
    }

    private static void abilityTreeCorrelationKeepsAmbiguousRuntimeStateUnknown() {
        WynnAbilityDataService.AbilityNode node = new WynnAbilityDataService.AbilityNode(
            "arrowbomb", "Arrow Bomb", 4, 1, 2, 1, List.of("Test ability"),
            Map.of("ABILITY_POINTS", 1), List.of("bowProficiency"), List.of(),
            "minecraft:paper", "Arrow Bomb", List.of(123));
        WynnAbilityDataService.AbilityTree official = new WynnAbilityDataService.AbilityTree(
            "ARCHER", 1L, Map.of(), List.of(node), List.of("1"));
        AbilityTreeDebugCollector.Result noScreen = new AbilityTreeDebugCollector.Result(
            1L, 10L, null, null, null, null, null, null, null, List.of(), List.of(), List.of(),
            "unclassified", "no runtime evidence");
        AbilityTreeSemanticCollector.Result unknown = AbilityTreeSemanticCollector.correlate(official, noScreen);
        check(!unknown.abilityTreeRecognized() && unknown.availablePoints() == null
                && "UNKNOWN".equals(unknown.nodes().getFirst().state()),
            "without live screen evidence, Ability Tree nodes and point counts must remain unknown");

        ItemInspector.ItemView item = new ItemInspector.ItemView("minecraft:paper", "Arrow Bomb", null, 1,
            List.of(new ItemInspector.TooltipLine("Available", null)), "components-hash");
        OpenContainerCollector.SlotView slot = new OpenContainerCollector.SlotView(
            5, 5, "OPEN_CONTAINER", false, true, item);
        OpenContainerCollector.Result container = new OpenContainerCollector.Result(
            true, "", 100L, "net.minecraft.client.gui.screens.inventory.ContainerScreen",
            "net.minecraft.world.inventory.ChestMenu", 9, 4, 55L, 55L, "Ability Tree", null,
            90, 1, null, null, List.of(slot), false, null, null, "");
        TextCaptureStore.CapturedText points = new TextCaptureStore.CapturedText(
            "gui.text", "Available Ability Points: 3", 1, 1, null, false, 1L, 1L, 1L);
        AbilityTreeDebugCollector.Result evidence = new AbilityTreeDebugCollector.Result(
            100L, 55L, container.screenClass(), "Ability Tree", container.menuClass(), 9, 4,
            container, null, List.of(), List.of(points), List.of(), "unclassified", "raw evidence");
        AbilityTreeSemanticCollector.Result matched = AbilityTreeSemanticCollector.correlate(official, evidence);
        check(matched.abilityTreeRecognized() && matched.availablePoints() == 3
                && matched.nodes().getFirst().menuSlot() == 5
                && "AVAILABLE".equals(matched.nodes().getFirst().state()),
            "an exact official icon identity plus explicit live state text should be correlated without slot guessing");
    }

    private static void abilitySelectionPreflightRejectsUnknownOrUnmetState() {
        WynnAbilityDataService.AbilityNode node = new WynnAbilityDataService.AbilityNode(
            "arrowbomb", "Arrow Bomb", 4, 1, 2, 1, List.of("Test node"),
            Map.of("ABILITY_POINTS", 1), List.of(), List.of(), "minecraft:paper", "Arrow Bomb", List.of(123));
        WynnAbilityDataService.AbilityTree tree = new WynnAbilityDataService.AbilityTree(
            "ARCHER", 1L, Map.of(), List.of(node), List.of("1"));
        AbilityTreeSemanticCollector.NodeView available = abilityView(node, "AVAILABLE", "EXACT", Map.of("ABILITY_POINTS", 1), List.of());
        AbilityTreeSemanticCollector.Result live = abilityResult(3, 55L, 4, List.of(available));
        WynnAbilitySelectionService.SelectionPlan plan = WynnAbilitySelectionService.plan(
            tree, live, "arrowbomb", "ARCHER", "AbilityTreeScreen", 9, 55L);
        check(plan.cost() == 1 && plan.availablePoints() == 3 && plan.runtimeNode().menuSlot() == 4,
            "a known available exact node with sufficient AP should pass semantic preflight");

        expectUiCode("UNKNOWN_ABILITY", () -> WynnAbilitySelectionService.plan(
            tree, live, "missing", "ARCHER", "AbilityTreeScreen", 9, 55L));
        expectUiCode("AMBIGUOUS_ABILITY", () -> WynnAbilitySelectionService.plan(
            tree, abilityResult(3, 55L, 4, List.of(abilityView(node, "AVAILABLE", "AMBIGUOUS", Map.of("ABILITY_POINTS", 1), List.of()))),
            "arrowbomb", "ARCHER", "AbilityTreeScreen", 9, 55L));
        expectUiCode("ABILITY_ALREADY_SELECTED", () -> WynnAbilitySelectionService.plan(
            tree, abilityResult(3, 55L, 4, List.of(abilityView(node, "SELECTED", "EXACT", Map.of("ABILITY_POINTS", 1), List.of()))),
            "arrowbomb", "ARCHER", "AbilityTreeScreen", 9, 55L));
        expectUiCode("ABILITY_LOCKED", () -> WynnAbilitySelectionService.plan(
            tree, abilityResult(3, 55L, 4, List.of(abilityView(node, "LOCKED", "EXACT", Map.of("ABILITY_POINTS", 1), List.of()))),
            "arrowbomb", "ARCHER", "AbilityTreeScreen", 9, 55L));
        expectUiCode("INSUFFICIENT_AP", () -> WynnAbilitySelectionService.plan(
            tree, abilityResult(0, 55L, 4, List.of(available)), "arrowbomb", "ARCHER", "AbilityTreeScreen", 9, 55L));
        expectUiCode("STALE_REVISION", () -> WynnAbilitySelectionService.plan(
            tree, live, "arrowbomb", "ARCHER", "AbilityTreeScreen", 9, 54L));
        expectUiCode("SCREEN_CHANGED", () -> WynnAbilitySelectionService.plan(
            tree, live, "arrowbomb", "ARCHER", "OtherScreen", 9, 55L));
        expectUiCode("WRONG_CLASS", () -> WynnAbilitySelectionService.plan(
            tree, abilityResult(3, 55L, 4, List.of(available), false), "arrowbomb", "ARCHER", "AbilityTreeScreen", 9, 55L));

        WynnAbilityDataService.AbilityNode requiresNode = new WynnAbilityDataService.AbilityNode(
            "arrowbomb", "Arrow Bomb", 4, 1, 2, 1, List.of("Test node"),
            Map.of("ABILITY_POINTS", 1, "NODE", "bowProficiency"), List.of(), List.of(),
            "minecraft:paper", "Arrow Bomb", List.of(123));
        WynnAbilityDataService.AbilityTree parentTree = new WynnAbilityDataService.AbilityTree(
            "ARCHER", 1L, Map.of(), List.of(requiresNode), List.of("1"));
        expectUiCode("NODE_REQUIREMENT_NOT_MET", () -> WynnAbilitySelectionService.plan(
            parentTree, live, "arrowbomb", "ARCHER", "AbilityTreeScreen", 9, 55L));
        WynnAbilityDataService.AbilityNode requiresArchetype = new WynnAbilityDataService.AbilityNode(
            "arrowbomb", "Arrow Bomb", 4, 1, 2, 1, List.of("Test node"),
            Map.of("ABILITY_POINTS", 1, "ARCHETYPE", "boltslinger"), List.of(), List.of(),
            "minecraft:paper", "Arrow Bomb", List.of(123));
        WynnAbilityDataService.AbilityTree archetypeTree = new WynnAbilityDataService.AbilityTree(
            "ARCHER", 1L, Map.of(), List.of(requiresArchetype), List.of("1"));
        expectUiCode("ARCHETYPE_REQUIREMENT_NOT_MET", () -> WynnAbilitySelectionService.plan(
            archetypeTree, live, "arrowbomb", "ARCHER", "AbilityTreeScreen", 9, 55L));
        AbilityTreeSemanticCollector.Result archetypeSelected = new AbilityTreeSemanticCollector.Result(
            "AbilityTreeScreen", 9, 4, "ARCHER", true, true, 3, 1, 55L,
            List.of(available), List.of(new AbilityTreeSemanticCollector.ArchetypeView("boltslinger", "Boltslinger",
                "SELECTED", "EXACT", 29, 29, "minecraft:potion", "abilityTree.boltslinger", List.of(193),
                "minecraft:potion", "Boltslinger", List.of("Selected"))), List.of("boltslinger"), "synthetic archetype evidence");
        WynnAbilitySelectionService.SelectionPlan archetypePlan = WynnAbilitySelectionService.plan(archetypeTree, archetypeSelected,
            "arrowbomb", "ARCHER", "AbilityTreeScreen", 9, 55L);
        check(archetypePlan.cost() == 1,
            "official ARCHETYPE requirements are verified from uniquely matched selected archetype items");
        WynnAbilityDataService.AbilityNode hasLock = new WynnAbilityDataService.AbilityNode(
            "arrowbomb", "Arrow Bomb", 4, 1, 2, 1, List.of("Test node"),
            Map.of("ABILITY_POINTS", 1), List.of(), List.of("bowProficiency"),
            "minecraft:paper", "Arrow Bomb", List.of(123));
        WynnAbilityDataService.AbilityTree lockTree = new WynnAbilityDataService.AbilityTree(
            "ARCHER", 1L, Map.of(), List.of(hasLock), List.of("1"));
        AbilityTreeSemanticCollector.NodeView selectedLock = abilityView(
            new WynnAbilityDataService.AbilityNode("bowProficiency", "Bow Proficiency", 2, 1, 1, 1,
                List.of(), Map.of("ABILITY_POINTS", 1), List.of(), List.of(), "minecraft:paper",
                "Bow Proficiency", List.of(111)), "SELECTED", "EXACT", Map.of("ABILITY_POINTS", 1), List.of());
        expectUiCode("ABILITY_LOCKED", () -> WynnAbilitySelectionService.plan(
            lockTree, abilityResult(3, 55L, 4, List.of(available, selectedLock)),
            "arrowbomb", "ARCHER", "AbilityTreeScreen", 9, 55L));

        AbilityTreeSemanticCollector.NodeView selected = abilityView(node, "SELECTED", "EXACT", Map.of("ABILITY_POINTS", 1), List.of());
        AbilityTreeSemanticCollector.Result after = abilityResult(2, 56L, 5, List.of(selected));
        check(WynnAbilitySelectionService.selectionVerified(after, plan),
            "successful selection simulation must require selected live node, AP decrease, and revision/state update");
        check(!WynnAbilitySelectionService.selectionVerified(abilityResult(3, 56L, 5, List.of(available)), plan),
            "postcondition failure must not count as verified selection");
    }

    private static AbilityTreeSemanticCollector.NodeView abilityView(
        WynnAbilityDataService.AbilityNode node, String state, String confidence,
        Map<String, Object> requirements, List<String> locks
    ) {
        return new AbilityTreeSemanticCollector.NodeView(node.id(), node.name(), state, confidence,
            List.of("slot", "renderedItem", "itemIdentity", "tooltip"), 1, node.slot(), 120, 80,
            node.page(), node.slot(), Map.of("x", node.x(), "y", node.y()), requirements, node.links(), locks,
            "minecraft:paper", "Arrow Bomb", List.of(123),
            "minecraft:paper", node.name(), 1, "component-sha256", List.of("Available"),
            true, "synthetic exact live identity");
    }

    private static AbilityTreeSemanticCollector.Result abilityResult(
        int points, long revision, int containerStateId, List<AbilityTreeSemanticCollector.NodeView> nodes
    ) {
        return abilityResult(points, revision, containerStateId, nodes, true);
    }

    private static AbilityTreeSemanticCollector.Result abilityResult(
        int points, long revision, int containerStateId, List<AbilityTreeSemanticCollector.NodeView> nodes,
        boolean classVerified
    ) {
        return new AbilityTreeSemanticCollector.Result("AbilityTreeScreen", 9, containerStateId,
            "ARCHER", classVerified, true, points, 1, revision, nodes, List.of(), List.of(), "synthetic evidence");
    }

    private static void modMenuSettingsPersistAndDisarmSafely() throws IOException {
        Path path = Path.of("build", "ui-config-regression.properties");
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.deleteIfExists(path);
        try {
            Properties original = new Properties();
            original.setProperty("token", "preserve-this-value");
            try (var out = Files.newOutputStream(path)) { original.store(out, "test"); }
            BridgeConfig config = config(8765, false, "preserve-this-value", true);
            UiActionGate.INSTANCE.arm(new Object(), 30, UiActionGate.Category.BANK, 2);
            config.updateUiSettings(path, false, 999);
            check(!config.allowUiActions() && config.uiActionsMaxArmSeconds() == 300,
                "Mod Menu settings must clamp timeout to 300 seconds and apply the disabled state");
            check(!UiActionGate.INSTANCE.status().armed(),
                "turning UI actions off in settings must clear the local arm and its allowances immediately");
            Properties saved = new Properties();
            try (var in = Files.newInputStream(path)) { saved.load(in); }
            check("false".equals(saved.getProperty("allowUiActions"))
                    && "300".equals(saved.getProperty("uiActions.maxArmSeconds"))
                    && "preserve-this-value".equals(saved.getProperty("token")),
                "settings save must change only the two UI action keys and retain the Bearer token");
            config.updateUiSettings(path, true, 1);
            check(config.allowUiActions() && config.uiActionsMaxArmSeconds() == 30,
                "Mod Menu settings must clamp too-small timeouts to 30 seconds");
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void characterInfoAndSkillValidationFailClosed() {
        check(!WynnSemanticUiService.characterInfoRecognized("Bank", true, 3, Map.of("Strength", 1, "Agility", 2)),
            "a non-Character Info screen must not be recognized from skill-like content alone");
        check(!WynnSemanticUiService.characterInfoRecognized("Character Info", false, 3, Map.of()),
            "a Character Info title without a recognized skill button must remain unrecognized");
        check(WynnSemanticUiService.characterInfoRecognized("Character Info", true, 3, Map.of()),
            "Character Info needs both a semantic title and live skill/point evidence");

        WynnSemanticUiService.Skill intelligence = WynnSemanticUiService.Skill.parse("intelligence");
        WynnSemanticUiService.validateSkillAssignment(intelligence, 2, 3, 4, 1);
        expectUiCode("INSUFFICIENT_SKILL_POINTS", () ->
            WynnSemanticUiService.validateSkillAssignment(intelligence, 2, 1, 4, 1));
        expectUiCode("SKILL_VALUE_UNKNOWN", () ->
            WynnSemanticUiService.validateSkillAssignment(intelligence, 1, 3, null, 1));
        expectUiCode("SKILL_BUTTON_NOT_UNIQUE", () ->
            WynnSemanticUiService.validateSkillAssignment(intelligence, 1, 3, 4, 0));
        expectUiCode("UNKNOWN_SKILL", () -> WynnSemanticUiService.Skill.parse("luck"));

        WynnSemanticUiService.Snapshot before = new WynnSemanticUiService.Snapshot(
            1L, "CharacterInfoScreen", "ChestMenu", "Character Info", 5, 9, 4, 20L,
            List.of(), List.of(), 3, 2, Map.of("Intelligence", 4), true, false, true);
        WynnSemanticUiService.Snapshot after = new WynnSemanticUiService.Snapshot(
            2L, "CharacterInfoScreen", "ChestMenu", "Character Info", 5, 9, 5, 21L,
            List.of(), List.of(), 2, 2, Map.of("Intelligence", 5), true, false, true);
        WynnSemanticUiService.validateContext(before, "CharacterInfoScreen", 9, 20L);
        expectUiCode("STATE_CHANGED", () -> WynnSemanticUiService.validateContext(
            before, "CharacterInfoScreen", 9, 21L));
        WynnSemanticUiService.SkillClick click = new WynnSemanticUiService.SkillClick(
            before, before, intelligence);
        check(WynnSemanticUiService.skillClickVerified(after, click),
            "skill success requires both a one-point skill increase and one unassigned point consumed after state sync");
        WynnSemanticUiService.Snapshot changedGui = new WynnSemanticUiService.Snapshot(
            2L, "AbilityTreeScreen", "ChestMenu", "Ability Tree", 6, 9, 5, 21L,
            List.of(), List.of(), 2, 2, Map.of("Intelligence", 5), false, true, true);
        check(!WynnSemanticUiService.skillClickVerified(changedGui, click),
            "a GUI transition during assignment must stop the operation and never count as verified");
    }

    private static void officialPlayerAbilityMapsAreFlattenedFromPageGroups() {
        Map<String, Object> officialResponse = Map.of("1", List.of(Map.of(
            "type", "ability",
            "coordinates", Map.of("x", 5, "y", 1),
            "meta", Map.of("id", "arrowbomb", "page", 1),
            "family", List.of("arrowbomb")
        )));
        List<Map<String, Object>> abilities = WynnAbilityDataService.normalizePlayerAbilities(officialResponse);
        check(abilities.size() == 1 && "arrowbomb".equals(abilities.getFirst().get("id"))
                && Integer.valueOf(1).equals(abilities.getFirst().get("page")),
            "official player abilities grouped by page must be flattened while retaining node id/page metadata");
    }

    private static byte[] openCharacterInfoRequest() {
        return ("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{" +
            "\"name\":\"wynn_open_character_info\",\"arguments\":{" +
            "\"expectedScreen\":\"InventoryScreen\",\"expectedSyncId\":0,\"expectedRevision\":1}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bankDepositRequest() {
        return ("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\",\"params\":{" +
            "\"name\":\"wynn_deposit_bank_item\",\"arguments\":{" +
            "\"inventorySlot\":14,\"expectedItemId\":\"minecraft:emerald\",\"expectedItemName\":\"Test Emerald\",\"expectedBankPage\":2," +
            "\"expectedScreen\":\"ContainerScreen\",\"expectedSyncId\":9,\"expectedRevision\":165}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] abilitySelectionRequest() {
        return ("{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"tools/call\",\"params\":{" +
            "\"name\":\"wynn_select_ability\",\"arguments\":{" +
            "\"abilityId\":\"arrowbomb\",\"classId\":\"archer\",\"expectedScreen\":\"AbilityTreeScreen\"," +
            "\"expectedSyncId\":9,\"expectedRevision\":55}}}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static UiActionService.GuiSnapshot bankPageSnapshot(int page, int pageCount) {
        List<UiActionService.SlotSnapshot> slots = new ArrayList<>(90);
        for (int menuSlot = 0; menuSlot < 90; menuSlot++) {
            int inventorySlot = menuSlot < 54 ? -1 : menuSlot < 81 ? menuSlot - 45 : menuSlot - 81;
            slots.add(new UiActionService.SlotSnapshot(menuSlot, inventorySlot, true, true,
                new UiActionService.ItemSnapshot(true, "", "", 0, "", 0)));
        }
        slots.set(10, slot(10, -1, "minecraft:leather_helmet", "Test Helmet", 1));
        slots.set(46, slot(46, -1, "minecraft:paper", "Quick Actions", 1));
        slots.set(47, slot(47, -1, "minecraft:paper", "Storage Type", 1));
        if (page > 1) slots.set(51, slot(51, -1, "minecraft:paper", "Page " + (page - 1) + " <<<<<", 1));
        if (page < pageCount) slots.set(52, slot(52, -1, "minecraft:paper", "Page " + (page + 1) + " >>>>>", 1));
        return new UiActionService.GuiSnapshot(1L,
            "net.minecraft.client.gui.screens.inventory.ContainerScreen",
            "net.minecraft.world.inventory.ChestMenu", "Page " + page + " of " + pageCount,
            5, 9, 48, 165L, List.copyOf(slots),
            new UiActionService.ItemSnapshot(true, "", "", 0, "", 0));
    }

    private static UiActionService.SlotSnapshot slot(int menuSlot, int inventorySlot, String itemId, String name, int count) {
        return new UiActionService.SlotSnapshot(menuSlot, inventorySlot, true, true,
            new UiActionService.ItemSnapshot(false, itemId, name, count, "{}", 64));
    }

    private static UiActionService.GuiSnapshot snapshot(
        UiActionService.GuiSnapshot original,
        List<UiActionService.SlotSnapshot> slots,
        UiActionService.ItemSnapshot carried
    ) {
        return new UiActionService.GuiSnapshot(original.capturedAt(), original.screenClass(), original.menuClass(),
            original.title(), original.screenIdentity(), original.syncId(), original.stateId(), original.stateRevision(),
            List.copyOf(slots), carried);
    }

    private static UiActionService.SlotSnapshot emptySlot(int menuSlot, int inventorySlot) {
        return new UiActionService.SlotSnapshot(menuSlot, inventorySlot, true, true,
            new UiActionService.ItemSnapshot(true, "", "", 0, "", 0));
    }

    private static UiActionService.GuiSnapshot withTitle(UiActionService.GuiSnapshot original, String title) {
        return new UiActionService.GuiSnapshot(original.capturedAt(), original.screenClass(), original.menuClass(),
            title, original.screenIdentity(), original.syncId(), original.stateId(), original.stateRevision(),
            original.slots(), original.carriedItem());
    }

    private static Set<String> toolNames(String body) {
        Map<String, Object> rpc = Json.object(Json.parse(body));
        Map<String, Object> result = Json.object(rpc.get("result"));
        List<Map<String, Object>> toolList = tools(result);
        return names(toolList);
    }

    private static HttpResponse request(int port, String host, String extraHeader, String authorization, byte[] body) throws IOException {
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), port)) {
            socket.setSoTimeout(3000);
            var out = socket.getOutputStream();
            StringBuilder headers = new StringBuilder()
                .append("POST /mcp HTTP/1.1\r\n")
                .append("Host: ").append(host).append("\r\n")
                .append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Connection: close\r\n");
            if (extraHeader != null) headers.append(extraHeader);
            if (authorization != null) headers.append("Authorization: ").append(authorization).append("\r\n");
            headers.append("\r\n");
            out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(response);
            String raw = response.toString(StandardCharsets.UTF_8);
            int firstLineEnd = raw.indexOf("\r\n");
            String statusLine = firstLineEnd < 0 ? raw : raw.substring(0, firstLineEnd);
            int status = Integer.parseInt(statusLine.split(" ")[1]);
            int bodyStart = raw.indexOf("\r\n\r\n");
            return new HttpResponse(status, bodyStart < 0 ? "" : raw.substring(bodyStart + 4));
        }
    }

    private static int freeLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static void expectGate(String code, String message, Runnable action) {
        try {
            action.run();
        } catch (UiActionGate.GateException e) {
            check(code.equals(e.code()), "expected UI gate code " + code);
            if (message != null) check(message.equals(e.getMessage()), "expected safe gate message");
            return;
        }
        throw new AssertionError("Expected UI gate exception " + code);
    }

    private static void expectUiCode(String code, Runnable action) {
        try {
            action.run();
        } catch (UiActionService.UiActionException e) {
            check(code.equals(e.code()), "expected UI validation code " + code);
            return;
        }
        throw new AssertionError("Expected UI action validation exception " + code);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private record HttpResponse(int status, String body) {}

    private static final class FakeClock {
        private final AtomicLong nanos = new AtomicLong();
        private final AtomicLong millis = new AtomicLong(1_000_000L);

        UiActionGate newGate() {
            return new UiActionGate(nanos::get, millis::get, 750L);
        }

        void advanceMillis(long amount) {
            nanos.addAndGet(amount * 1_000_000L);
            millis.addAndGet(amount);
        }
    }
}
